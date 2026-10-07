"""OpenAI wire mock. No outbound clients and no request/credential logging."""
import asyncio
import base64
from dataclasses import dataclass, field
import json
import math
import os
import random
import struct
import time
import uuid
import zlib

from jsonschema.exceptions import SchemaError, ValidationError
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, StreamingResponse
from starlette.routing import Route

from schema_gen import generate, UnsupportedSchema
from templates import content, purpose


def number(env, name, default, maximum=None):
    value = float(env.get(name, default))
    if not math.isfinite(value) or value < 0 or (maximum is not None and value > maximum):
        raise ValueError('Invalid setting: ' + name)
    return value


def delays(env, name):
    values = json.loads(env.get(name, '{}'))
    if not isinstance(values, dict) or any(not isinstance(v, (int,float)) or isinstance(v,bool) or not math.isfinite(v) or v < 0 for v in values.values()):
        raise ValueError('Invalid delay map: ' + name)
    return values


@dataclass(frozen=True)
class Config:
    first_token: float = 0.3
    stream_total: float = 7.5
    chunks: int = 60
    complete_delay: float = 2.9
    image_delay: float = 2.9
    extra_delay: float = 0.0
    error_rate: float = 0.0
    error_429_share: float = 0.5
    disconnect_rate: float = 0.0
    model_delays: dict = field(default_factory=dict)
    purpose_delays: dict = field(default_factory=dict)

    @classmethod
    def from_env(cls, env=None):
        env = os.environ if env is None else env
        c = cls(first_token=number(env, 'MOCK_FIRST_TOKEN_SECONDS', .3),
                stream_total=number(env, 'MOCK_STREAM_TOTAL_SECONDS', 7.5),
                chunks=int(env.get('MOCK_STREAM_CHUNKS', '60')),
                complete_delay=number(env, 'MOCK_COMPLETE_SECONDS', 2.9),
                image_delay=number(env, 'MOCK_IMAGE_SECONDS', 2.9),
                extra_delay=number(env, 'MOCK_EXTRA_DELAY_SECONDS', 0),
                error_rate=number(env, 'MOCK_ERROR_RATE', 0, 1),
                error_429_share=number(env, 'MOCK_ERROR_429_SHARE', .5, 1),
                disconnect_rate=number(env, 'MOCK_STREAM_DISCONNECT_RATE', 0, 1),
                model_delays=delays(env, 'MOCK_MODEL_DELAYS_JSON'),
                purpose_delays=delays(env, 'MOCK_PURPOSE_DELAYS_JSON'))
        if not 2 <= c.chunks <= 4096 or c.stream_total < c.first_token:
            raise ValueError('Invalid stream duration/chunks')
        return c


class StreamInterrupted(ConnectionError):
    """Abort HTTP body without finish/usage/DONE, so SDK sees a transport failure."""


def usage(text):
    output = max(1, math.ceil(len(text)/4))
    return dict(prompt_tokens=128, completion_tokens=output, total_tokens=128+output,
                prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=128,
                prompt_tokens_details=dict(cached_tokens=0))


def frame(payload):
    return ('data: '+(payload if isinstance(payload,str) else json.dumps(payload, ensure_ascii=False))+'\n\n').encode()


async def stream_frames(text, model, config, interrupted=False):
    count = min(config.chunks, len(text))
    identity = 'chatcmpl-mock-'+uuid.uuid4().hex
    created = int(time.time())
    def chunk(choices, **extra):
        return dict(id=identity, object='chat.completion.chunk', created=created, model=model, choices=choices, **extra)
    started = asyncio.get_running_loop().time()
    for i in range(count):
        deadline = started + config.extra_delay + config.first_token + (config.stream_total-config.first_token)*i/(count-1 if count > 1 else 1)
        await asyncio.sleep(max(0, deadline-asyncio.get_running_loop().time()))
        if interrupted and i >= max(1,count//2):
            raise StreamInterrupted('Injected mock stream interruption')
        piece = text[len(text)*i//count:len(text)*(i+1)//count]
        delta = dict(content=piece)
        if i == 0:
            delta['role'] = 'assistant'
        yield frame(chunk([dict(index=0, delta=delta, finish_reason=None)]))
    yield frame(chunk([dict(index=0, delta={}, finish_reason='stop')]))
    yield frame(chunk([], usage=usage(text)))
    yield frame('[DONE]')


def png():
    def part(kind, data):
        return struct.pack('!I',len(data))+kind+data+struct.pack('!I',zlib.crc32(kind+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+part(b'IHDR',struct.pack('!2I5B',1,1,8,6,0,0,0))+part(b'IDAT',zlib.compress(b'\x00\x40\x80\xc0\xff'))+part(b'IEND',b'')

PNG = base64.b64encode(png()).decode()
# A fixed one-pixel WebP for manyak-ai's output_format=webp request.
WEBP = 'UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA'


def error(status, message):
    return JSONResponse(dict(error=dict(message=message, type='mock_error', code=str(status))), status_code=status)


def authorized(request):
    auth = request.headers.get('authorization','').split(None,1)
    return len(auth) == 2 and auth[0].lower() == 'bearer' and bool(auth[1].strip())


def create_app(config=None, rng=None):
    config = Config.from_env() if config is None else config
    rng = random.random if rng is None else rng

    async def failure():
        if rng() < config.error_rate:
            await asyncio.sleep(config.extra_delay)
            return error(429 if rng() < config.error_429_share else 500, 'Injected mock error')
        return None

    async def chat(request: Request):
        if not authorized(request):
            return error(401,'A nonempty Bearer key is required')
        if int(request.headers.get('content-length','0')) > 2*1024*1024:
            return error(413,'Request too large')
        try:
            body = await request.json()
            if not isinstance(body,dict) or not isinstance(body.get('model'),str) or not body['model'] or not isinstance(body.get('messages'),list):
                return error(400,'model and messages are required')
            if not isinstance(body.get('response_format',{}),dict) or not isinstance(body.get('stream',False),bool):
                return error(400,'Invalid response_format/stream')
            if body.get('tools'):
                return error(400,'Tools are not used by the supported manyak-ai calls')
            kind = purpose(body)
            if kind == 'schema':
                text = json.dumps(generate(body['response_format']['json_schema']['schema']), ensure_ascii=False)
            else:
                text = content(body, kind)
        except (ValueError, TypeError, KeyError, SchemaError, ValidationError, UnsupportedSchema):
            return error(400,'Unsupported request/schema; supply a supported loadtest request')
        failed = await failure()
        if failed is not None:
            return failed
        if body.get('stream'):
            return StreamingResponse(stream_frames(text, body['model'], config, rng() < config.disconnect_rate), media_type='text/event-stream', headers={'Cache-Control':'no-cache','X-Accel-Buffering':'no'})
        delay = config.model_delays.get(body['model'], config.purpose_delays.get(kind,config.complete_delay))
        await asyncio.sleep(config.extra_delay+delay)
        return JSONResponse(dict(id='chatcmpl-mock-'+uuid.uuid4().hex, object='chat.completion', created=int(time.time()), model=body['model'],
                                 choices=[dict(index=0,message=dict(role='assistant',content=text,refusal=None),finish_reason='stop')], usage=usage(text)))

    async def image(request: Request):
        if not authorized(request):
            return error(401,'A nonempty Bearer key is required')
        try:
            if request.url.path.endswith('/edits'):
                async with request.form(max_files=4,max_fields=32,max_part_size=8*1024*1024) as form:
                    if not (form.get('image') or form.get('image[]')):
                        return error(400,'image multipart part is required')
                    n = int(form.get('n',1))
                    fmt = form.get('output_format','png')
            else:
                body = await request.json()
                n = int(body.get('n',1))
                fmt = body.get('output_format','png')
            if n != 1 or fmt not in ('png','webp'):
                return error(400,'This mock supports n=1 and png/webp')
        except (ValueError,TypeError,AttributeError):
            return error(400,'Invalid image request')
        failed = await failure()
        if failed is not None:
            return failed
        await asyncio.sleep(config.extra_delay+config.image_delay)
        return JSONResponse(dict(created=int(time.time()),data=[dict(b64_json=WEBP if fmt == 'webp' else PNG)],
                                 usage=dict(input_tokens=128,output_tokens=1,total_tokens=129)))

    async def health(request):
        return JSONResponse(dict(status='ok'))

    return Starlette(routes=[Route('/health',health), Route('/v1/chat/completions',chat,methods=['POST']),
                             Route('/v1/images/generations',image,methods=['POST']), Route('/v1/images/edits',image,methods=['POST'])])
