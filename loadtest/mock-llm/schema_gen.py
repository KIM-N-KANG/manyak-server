"""Bounded JSON Schema synthesis; reject unsupported schemas rather than lie."""
import copy
import math
import re
from re import _parser, _constants
from jsonschema import Draft202012Validator
from referencing import Registry
from referencing.exceptions import NoSuchResource, Unresolvable


class UnsupportedSchema(ValueError):
    pass


def deny_retrieval(uri):
    # No network or filesystem retrieval, including refs rebased by $id.
    raise NoSuchResource(ref=uri)


LOCAL_REGISTRY = Registry(retrieve=deny_retrieval)


def validator(schema):
    return Draft202012Validator(schema, registry=LOCAL_REGISTRY)


def pattern_sample(pattern):
    def walk(tokens):
        out = ''
        for op, arg in tokens:
            if op == _constants.LITERAL:
                out += chr(arg)
            elif op == _constants.IN:
                if arg and arg[0][0] == _constants.NEGATE:
                    out += next(c for c in 'xA0_' if not re.fullmatch('[' + ''.join(re.escape(chr(v)) for o,v in arg[1:] if o == _constants.LITERAL) + ']', c))
                else:
                    kind, val = arg[0]
                    out += chr(val[0] if kind == _constants.RANGE else val) if kind in (_constants.RANGE, _constants.LITERAL) else '0'
            elif op in (_constants.MAX_REPEAT, _constants.MIN_REPEAT):
                low, _, inner = arg
                if low > 4096:
                    raise UnsupportedSchema('pattern too long')
                out += walk(inner) * low
            elif op == _constants.SUBPATTERN:
                out += walk(arg[-1])
            elif op == _constants.BRANCH:
                out += walk(arg[1][0])
            elif op == _constants.ANY:
                out += 'x'
            elif op == _constants.CATEGORY:
                out += '0' if arg == _constants.CATEGORY_DIGIT else 'x'
            elif op != _constants.AT:
                raise UnsupportedSchema('unsupported pattern')
        return out
    return walk(_parser.parse(pattern, 0))


def generate(schema):
    if not isinstance(schema, (dict, bool)):
        raise UnsupportedSchema('invalid schema')
    Draft202012Validator.check_schema(schema)
    def local_refs_only(node):
        if isinstance(node, dict):
            if '$dynamicRef' in node or '$recursiveRef' in node:
                raise UnsupportedSchema('dynamic/recursive refs are unsupported')
            if '$ref' in node and not node['$ref'].startswith('#/'):
                raise UnsupportedSchema('only local refs are supported')
            for value in node.values(): local_refs_only(value)
        elif isinstance(node, list):
            for value in node: local_refs_only(value)
    local_refs_only(schema)

    def make(node, depth=0, serial=0):
        if depth > 24:
            raise UnsupportedSchema('recursive schema')
        if node is False:
            raise UnsupportedSchema('unsatisfiable schema')
        if node is True:
            return None
        node = copy.deepcopy(node)
        if '$ref' in node:
            ref = node.pop('$ref')
            if not ref.startswith('#/'):
                raise UnsupportedSchema('only local refs are supported')
            target = schema
            for key in ref[2:].split('/'):
                target = target[key.replace('~1', '/').replace('~0', '~')]
            node = {**target, **node}
        if 'allOf' in node:
            for branch in node.pop('allOf'):
                if '$ref' in branch:
                    raise UnsupportedSchema('allOf refs unsupported')
                node['properties'] = {**node.get('properties', {}), **branch.get('properties', {})}
                node['required'] = list(dict.fromkeys(node.get('required', []) + branch.get('required', [])))
                node.update({k:v for k,v in branch.items() if k not in ('properties', 'required')})
        for key in ('oneOf', 'anyOf'):
            if key in node:
                branches = node.pop(key)
                for branch in branches:
                    try:
                        value = make({**node, **branch}, depth+1, serial)
                        if validator(schema).evolve(schema={**node, key:branches}).is_valid(value):
                            return value
                    except (UnsupportedSchema, KeyError, TypeError, ValueError):
                        pass
                raise UnsupportedSchema('no satisfiable union branch')
        if 'const' in node:
            return node['const']
        if 'enum' in node:
            return node['enum'][serial % len(node['enum'])]
        kind = node.get('type', 'object' if 'properties' in node else 'string')
        if isinstance(kind, list):
            kind = 'null' if 'null' in kind else kind[0]
        if kind == 'null':
            return None
        if kind == 'boolean':
            return False
        if kind in ('integer', 'number'):
            step = node.get('multipleOf', 1 if kind == 'integer' else 0.1)
            low = node.get('minimum', 0)
            if 'exclusiveMinimum' in node:
                low = max(low, node['exclusiveMinimum'] + step)
            value = math.ceil(low / step) * step + serial * step
            return int(value) if kind == 'integer' else value
        if kind == 'object':
            props = node.get('properties', {})
            required = list(node.get('required', []))
            for key in props:
                if len(required) >= node.get('minProperties', 0):
                    break
                if key not in required:
                    required.append(key)
            return {key:make(props.get(key, node.get('additionalProperties', {})), depth+1) for key in required}
        if kind == 'array':
            count = node.get('minItems', 0)
            if count > 128:
                raise UnsupportedSchema('array too large')
            prefixes = node.get('prefixItems', [])
            return [make(prefixes[i] if i < len(prefixes) else node.get('items', {}), depth+1, i if node.get('uniqueItems') else 0) for i in range(count)]
        if kind == 'string':
            minimum = node.get('minLength', 0)
            if minimum > 4096:
                raise UnsupportedSchema('string too long')
            value = {
                'uuid':'00000000-0000-4000-8000-000000000000',
                'date':'2026-01-01', 'date-time':'2026-01-01T00:00:00Z',
                'email':'mock@example.invalid', 'uri':'https://example.invalid/mock',
            }.get(node.get('format'), 'mock' + (str(serial) if serial else ''))
            if 'pattern' in node:
                value = pattern_sample(node['pattern'])
            value = value.ljust(minimum, 'x')
            return value[:node.get('maxLength', max(len(value), minimum))]
        raise UnsupportedSchema('unsupported type')

    try:
        value = make(schema)
        validator(schema).validate(value)
        return value
    except (KeyError, TypeError, ValueError, RecursionError, Unresolvable) as exc:
        raise UnsupportedSchema('cannot synthesize schema') from exc
