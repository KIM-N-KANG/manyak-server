"""unittest-compatible tests are also collected by pytest; no AI/provider network."""
import asyncio
import base64
from dataclasses import replace
import json
from pathlib import Path
import struct
import sys
import time
import unittest
from unittest.mock import patch
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import httpx
from jsonschema import Draft202012Validator
from app import Config, StreamInterrupted, create_app, stream_frames
from schema_gen import generate, UnsupportedSchema, validator
from referencing.exceptions import Unresolvable
from templates import characters, content

FAST = Config(first_token=0, stream_total=0, chunks=4, complete_delay=0, image_delay=0)
BODY = dict(model='deepseek-flash', messages=[dict(role='user', content='private prompt')])


class SchemaTests(unittest.TestCase):
    def test_nested_schema_refs_enum_nullable_array_and_bounds(self):
        schema = dict(type='object', additionalProperties=False, required=['mode','items','optional','code'], properties={
            'mode':dict(enum=['APPROVED','REJECTED']),
            'optional':dict(anyOf=[dict(type='null'),dict(type='string')]),
            'code':dict(type='string',pattern='^[A-Z]{3}[0-9]{2}$'),
            'items':dict(type='array',minItems=2,maxItems=2,uniqueItems=True,items={'$ref':'#/$defs/item'})},
            **{'$defs':{'item':dict(type='integer',minimum=2,maximum=4)}})
        result = generate(schema)
        Draft202012Validator(schema).validate(result)
        self.assertEqual(result['code'],'AAA00')
        self.assertEqual(result['items'],[2,3])

    def test_allof_and_string_lengths(self):
        schema = {'allOf':[dict(type='object',required=['a'],properties={'a':dict(type='string',minLength=7,maxLength=7)}), dict(required=['b'],properties={'b':dict(type='boolean')})]}
        Draft202012Validator(schema).validate(generate(schema))

    def test_unsupported_external_ref_does_not_fetch(self):
        with self.assertRaises(UnsupportedSchema):
            generate({'$ref':'https://example.invalid/schema'})

    def test_all_remote_ref_keywords_are_rejected_without_urlopen(self):
        with patch('urllib.request.urlopen', side_effect=AssertionError('network retrieval forbidden')) as fetch:
            for key in ('$ref','$dynamicRef','$recursiveRef'):
                for schema in ({key:'https://example.invalid/schema'}, {'anyOf':[{'type':'string'},{key:'https://example.invalid/schema'}]}):
                    with self.subTest(key=key,schema=schema), self.assertRaises(UnsupportedSchema):
                        generate(schema)
            fetch.assert_not_called()

    def test_registry_blocks_retrieval_even_without_preflight(self):
        with patch('urllib.request.urlopen', side_effect=AssertionError('network retrieval forbidden')) as fetch:
            for key in ('$ref','$dynamicRef'):
                schema={key:'https://example.invalid/schema'}
                with self.subTest(key=key), self.assertRaises(Unresolvable):
                    validator(schema).validate('mock')
                with self.subTest(key=key,union=True), self.assertRaises(Unresolvable):
                    validator({}).evolve(schema={'anyOf':[schema]}).is_valid('mock')
            fetch.assert_not_called()

    def test_slash_character_names_survive_compile_and_storylines(self):
        body=dict(messages=[dict(role='user',content='[input_character_id: input-1] 이름: A/B / 성별: 여성 / 특징: (미정)\n[input_character_id: input-2] 이름: 조연 / 성별: 남성 / 특징: (미정)')])
        cards=characters(body)
        self.assertEqual([c['input_character_id'] for c in cards],['input-1','input-2'])
        self.assertEqual([c['name'] for c in cards],['A/B','조연'])
        body['messages'][0]['content']='1) 이름: A/B / 성별: 여성 / 특징: (미정)\n2) 이름: 조연 / 성별: 남성 / 특징: (미정)'
        data=json.loads(content(body,'storylines'))
        self.assertTrue(all('A/B' in x['storyline'] and '조연' in x['storyline'] for x in data['stories']))

    def test_config_defaults_overrides_and_invalid_values(self):
        c = Config.from_env({})
        self.assertEqual((c.first_token,c.stream_total,c.complete_delay),(.3,7.5,2.9))
        c = Config.from_env({'MOCK_PURPOSE_DELAYS_JSON':'{"choices":0.1}','MOCK_MODEL_DELAYS_JSON':'{"gpt-6-luna":0.2}'})
        self.assertEqual(c.model_delays['gpt-6-luna'],.2)
        for env in [{'MOCK_ERROR_RATE':'1.1'}, {'MOCK_EXTRA_DELAY_SECONDS':'nan'}, {'MOCK_STREAM_CHUNKS':'1'}, {'MOCK_STREAM_TOTAL_SECONDS':'.1'}, {'MOCK_MODEL_DELAYS_JSON':'{"a":-1}'}]:
            with self.assertRaises(ValueError): Config.from_env(env)


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def request(self, path, *, body=BODY, config=FAST, rng=lambda: .9, headers=None, **kwargs):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(config,rng)),base_url='http://mock') as client:
            return await client.post(path, json=body, headers=headers or {'Authorization':'Bearer fake'}, **kwargs)

    async def test_health_and_auth(self):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(FAST)),base_url='http://mock') as c:
            self.assertEqual((await c.get('/health')).json(),dict(status='ok'))
            for key in ['', 'Bearer ', 'Basic abc']:
                r = await c.post('/v1/chat/completions',json=BODY,headers={'Authorization':key})
                self.assertEqual(r.status_code,401)

    async def test_remote_refs_return_fixed_400_without_retrieval(self):
        with patch('urllib.request.urlopen', side_effect=AssertionError('network retrieval forbidden')) as fetch:
            for key in ('$ref','$dynamicRef','$recursiveRef'):
                for schema in ({key:'https://example.invalid/schema'}, {'anyOf':[{'type':'string'},{key:'https://example.invalid/schema'}]}):
                    body=BODY | dict(response_format=dict(type='json_schema',json_schema=dict(schema=schema)))
                    r=await self.request('/v1/chat/completions',body=body)
                    self.assertEqual(r.status_code,400)
                    self.assertEqual(r.json()['error']['message'],'Unsupported request/schema; supply a supported loadtest request')
                    self.assertNotIn('example.invalid',r.text)
            fetch.assert_not_called()

    async def test_nonstream_and_schema(self):
        r = await self.request('/v1/chat/completions')
        self.assertEqual(r.status_code,200)
        self.assertEqual(r.json()['choices'][0]['finish_reason'],'stop')
        schema = dict(type='object',required=['decision','issues'],properties={'decision':{'enum':['APPROVED','REJECTED']},'issues':dict(type='array',items=dict(type='string'))},additionalProperties=False)
        r = await self.request('/v1/chat/completions',body=BODY | {'response_format':dict(type='json_schema',json_schema=dict(schema=schema))})
        value = json.loads(r.json()['choices'][0]['message']['content'])
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value,dict(decision='APPROVED',issues=[]))

    async def test_stream_json_schema_is_generated_from_schema(self):
        schema=dict(type='object',required=['x'],properties={'x':dict(const='schema-value')})
        body=BODY | dict(stream=True,response_format=dict(type='json_schema',json_schema=dict(schema=schema)))
        r=await self.request('/v1/chat/completions',body=body)
        chunks=[json.loads(f[6:]) for f in r.text.strip().split('\n\n')[:-1]]
        text=''.join(c['choices'][0]['delta'].get('content','') for c in chunks if c['choices'])
        self.assertEqual(json.loads(text),dict(x='schema-value'))

    async def test_sse_wire_usage_done_and_content(self):
        r = await self.request('/v1/chat/completions',body=BODY | {'stream':True,'stream_options':{'include_usage':True}})
        self.assertEqual(r.status_code,200)
        self.assertIn('text/event-stream',r.headers['content-type'])
        frames = r.text.strip().split('\n\n')
        self.assertEqual(frames[-1],'data: [DONE]')
        chunks = [json.loads(f[6:]) for f in frames[:-1]]
        self.assertEqual(len({c['id'] for c in chunks}),1)
        self.assertTrue('usage' in chunks[-1] and chunks[-1]['choices']==[])
        self.assertEqual(chunks[-2]['choices'][0]['finish_reason'],'stop')
        joined = ''.join(c['choices'][0]['delta'].get('content','') for c in chunks if c['choices'])
        self.assertIn('동료:',joined)
        self.assertEqual(chunks[0]['choices'][0]['delta']['role'],'assistant')

    async def test_deadlines_and_additional_delay_are_async(self):
        c = replace(FAST,first_token=.025,stream_total=.075,extra_delay=.015)
        start = time.monotonic()
        times=[]
        async for f in stream_frames('abcdefgh','m',c):
            if b'"finish_reason": null' in f: times.append(time.monotonic()-start)
        self.assertGreaterEqual(times[0],.038)
        self.assertGreaterEqual(times[-1],.088)
        self.assertLess(times[-1],.25)

    async def test_nonstream_model_delay_precedence(self):
        c=replace(FAST,complete_delay=.15,extra_delay=.01,purpose_delays={'text':.1},model_delays={'deepseek-flash':.02})
        start=time.monotonic()
        r=await self.request('/v1/chat/completions',config=c)
        elapsed=time.monotonic()-start
        self.assertEqual(r.status_code,200)
        self.assertGreaterEqual(elapsed,.028)
        self.assertLess(elapsed,.1)

    async def test_injected_429_and_500(self):
        for share,status in [(1,429),(0,500)]:
            r=await self.request('/v1/chat/completions',config=replace(FAST,error_rate=1,error_429_share=share),rng=lambda:0)
            self.assertEqual(r.status_code,status)
            self.assertNotIn('private prompt',r.text)
            self.assertNotIn('fake',r.text)

    async def test_midstream_disconnect_has_no_done_or_usage(self):
        frames=[]
        with self.assertRaises(StreamInterrupted):
            async for f in stream_frames('abcdefgh','m',FAST,True):frames.append(f)
        self.assertTrue(frames)
        self.assertNotIn(b'[DONE]',b''.join(frames))
        self.assertNotIn(b'"usage"',b''.join(frames))
        with self.assertRaises(StreamInterrupted):
            await self.request('/v1/chat/completions',body=BODY | {'stream':True},config=replace(FAST,disconnect_rate=1),rng=lambda:0)

    async def test_images_generate_png_and_edit_multipart_webp(self):
        r=await self.request('/v1/images/generations',body=dict(model='image',prompt='private prompt'))
        image=base64.b64decode(r.json()['data'][0]['b64_json'],validate=True)
        self.assertTrue(image.startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertEqual(struct.unpack('!II',image[16:24]),(1,1))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(FAST)),base_url='http://mock') as c:
            r=await c.post('/v1/images/edits',headers={'Authorization':'Bearer fake'},data={'model':'image','output_format':'webp'},files={'image':('mock.png',image,'image/png')})
        self.assertEqual(r.status_code,200)
        webp=base64.b64decode(r.json()['data'][0]['b64_json'],validate=True)
        self.assertTrue(webp.startswith(b'RIFF') and webp[8:12]==b'WEBP')

    async def test_300_streams_overlap_instead_of_serial_delay(self):
        c=replace(FAST,first_token=.01,stream_total=.025)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(c)),base_url='http://mock') as client:
            async def consume():
                return await client.post('/v1/chat/completions',json=BODY | {'stream':True},headers={'Authorization':'Bearer fake'})
            start=time.monotonic()
            results=await asyncio.gather(*(consume() for _ in range(300)))
        self.assertEqual(len(results),300)
        self.assertTrue(all(x.status_code==200 and x.text.endswith('data: [DONE]\n\n') for x in results))
        self.assertLess(time.monotonic()-start,3)

    async def test_unknown_json_object_tools_and_bad_schema_fail_closed(self):
        for extra in [{'response_format':{'type':'json_object'}}, {'tools':[{'type':'function'}]}, {'response_format':{'type':'json_schema','json_schema':{'schema':False}}}]:
            r=await self.request('/v1/chat/completions',body=BODY | extra)
            self.assertEqual(r.status_code,400)


if __name__ == '__main__':
    unittest.main()
