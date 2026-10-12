"""Read-only real AI parser checks, without starting AI or contacting providers.

Imports actual Pydantic schemas through sys.path. Pure parser/validator functions
are compiled from AI source AST to avoid importing SDK/telemetry app startup.
AI files are read with git show from the selected revision into a temporary
import snapshot, so uncommitted AI changes do not affect validation.
"""
import argparse
import ast
import asyncio
import base64
import binascii
from contextlib import aclosing, nullcontext
from dataclasses import dataclass
import importlib.util
import json
import logging
from pathlib import Path
import re
import subprocess
import sys
import time
import tempfile
import unicodedata
from types import SimpleNamespace
from typing import Iterable

sys.dont_write_bytecode = True
from app import Config, create_app, stream_frames
from templates import content
import httpx


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--ai-root',type=Path,required=True)
    parser.add_argument('--revision',default='origin/dev')
    args=parser.parse_args()
    root=args.ai_root.resolve()
    def source(path):
        return subprocess.check_output(['git','show',f'{args.revision}:{path}'],cwd=root,text=True)
    # Import actual revision schemas without touching the AI working tree.
    snapshot = tempfile.TemporaryDirectory(prefix='mock-ai-validation-')
    for path in ['src/schemas/story.py','src/schemas/story_compile.py','src/schemas/response_meta.py','src/schemas/chat_turn.py','src/services/moderation/models.py']:
        target = Path(snapshot.name)/path
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(source(path))
    sys.path.insert(0,snapshot.name)
    from src.schemas.story import StoryItem, CharacterInput
    from src.schemas.story_compile import StorySpec, Ending, CharacterDescription
    from pydantic import TypeAdapter, ValidationError
    from src.schemas.chat_turn import CharacterImageMapping, TargetMainEventOut, EVENT_TOKEN, EVENT_COMPLETED, EVENT_ERROR, EVENT_CHARACTER_IMAGE
    from src.services.moderation.models import ModelDecision, ModerationResult, ModerationIssue, ModerationImageFailure, failure
    source('src/services/moderation/models.py')
    # Load standalone actual LLM types, bypassing the SDK registry package init.
    src=source('src/services/llm/base.py')
    ns={'__name__':'ai_mock_validation_base'}
    import types
    mod=types.ModuleType(ns['__name__']);sys.modules[mod.__name__]=mod
    exec(compile(src,str(root/'src/services/llm/base.py'),'exec'),mod.__dict__)

    def functions(path,names,namespace,assignments=()):
        tree=ast.parse(source(path))
        selected=[]
        for node in tree.body:
            if isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef)) and node.name in names:
                selected.append(node)
            elif isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id in assignments for t in node.targets):
                selected.append(node)
        assert len({n.name for n in selected if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef))}) == len(names)
        future=ast.ImportFrom(module='__future__',names=[ast.alias(name='annotations')],level=0)
        unit=ast.fix_missing_locations(ast.Module(body=[future]+selected,type_ignores=[]))
        exec(compile(unit,str(root/path),'exec'),namespace)
        return namespace

    sdk=functions('src/services/llm/openai_sdk.py',{'_text_of','_finish_reason_of','_usage_of'},
                  dict(logger=logging.getLogger('ai-parser'),TokenUsage=mod.TokenUsage,PROVIDER_OPENAI='openai'))
    def objectify(value):
        if isinstance(value,dict):return SimpleNamespace(**{k:objectify(v) for k,v in value.items()})
        if isinstance(value,list):return [objectify(v) for v in value]
        return value
    fast=Config(first_token=0,stream_total=0,chunks=12,complete_delay=0,image_delay=0)
    async def complete(body):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(fast)),base_url='http://mock') as client:
            response=await client.post('/v1/chat/completions',json=body,headers={'Authorization':'Bearer fake'})
            assert response.status_code==200
            value=objectify(response.json())
            assert sdk['_finish_reason_of'](value)=='stop'
            assert sdk['_usage_of'](value,SimpleNamespace(provider='deepseek')).input_tokens==128
            return sdk['_text_of'](value)

    story=functions('src/services/story_llm.py',{'_InvalidAiResponse','_validate_storylines','_is_empty','_as_dict','_is_valid_min_turns','_find_missing_keys','_endings_incomplete','_input_character_id','_input_character_ids_incomplete','_find_character_field_repairs','_missing_required_characters'},
                    dict(StoryItem=StoryItem,Ending=Ending,TypeAdapter=TypeAdapter,CharacterDescription=CharacterDescription,ValidationError=ValidationError,unicodedata=unicodedata),assignments={'_INPUT_CHARACTER_ID_FIELD','_CHARACTER_APPEARANCE_FIELDS','_CHARACTER_DESCRIPTION_ADAPTER'})
    choices=functions('src/services/chat_choices.py',{'_accumulate'}, {})
    judgement=functions('src/services/chat_judgement.py',{'JudgementResult','_sanitize'},
                        dict(dataclass=dataclass,TargetMainEventOut=TargetMainEventOut,logger=logging.getLogger('judgement')))
    moderation=functions('src/services/moderation/response.py',{'InvalidModerationResponse','parse_response'},
                         dict(ModelDecision=ModelDecision,ModerationResult=ModerationResult,ModerationIssue=ModerationIssue,ModerationImageFailure=ModerationImageFailure,failure=failure))
    def prompt(path):
        text=source(path)
        return text.split('## [SYSTEM]',1)[1].split('## [USER]',1)[0]
    prompt_ns=dict(re=re,GENDER_KO={'MALE':'남성','FEMALE':'여성'},PROVIDER_GOOGLE='google')
    for name,path in [('STORYLINES','prompt/story/STORYLINES-TEMPLATE.md'),('COMPILE','prompt/story/COMPILE-TEMPLATE.md')]:
        raw=source(path).split('## [SYSTEM]',1)[1]
        system,user=raw.split('## [USER]',1)
        prompt_ns['_'+name+'_SYSTEM']=system.strip().removesuffix('---').strip()
        prompt_ns['_'+name+'_USER']=user.strip()
    prompt_api=functions('src/services/prompt.py',{'_render','_format_character','_format_supporting_characters','_format_compile_supporting_characters','_format_lorebooks','build_storylines_prompt','build_compile_prompt'},prompt_ns)
    def body(system,user=''):
        return dict(model='deepseek-flash',messages=[dict(role='system',content=system),dict(role='user',content=user)],response_format={'type':'json_object'})

    async def checks():
        for names in [('검증동료',), ('A/B','조연')]:
            cards=[CharacterInput(name=name,gender='FEMALE') for name in names]
            system,user=prompt_api['build_storylines_prompt'](['판타지'],CharacterInput(name='주인공',gender='FEMALE'),cards)
            data=json.loads(await complete(body(system,user)))
            story['_validate_storylines'](data)
            assert all(all(name in item['storyline'] for name in names) for item in data['stories'])
        for names in [(), ('동료1',), tuple(f'동료{i}' for i in range(1,6)), ('A/B','조연')]:
            n=len(names)
            cards=[CharacterInput(name=name,gender='FEMALE') for name in names]
            system,user,_=prompt_api['build_compile_prompt']('가짜 스토리','',['판타지'],CharacterInput(name='주인공',gender='FEMALE'),cards,[])
            data=json.loads(await complete(body(system,user)))
            assert not story['_missing_required_characters'](data,names)
            assert story['_find_missing_keys'](data)==[]
            assert story['_find_character_field_repairs'](data)=={}
            assert not story['_endings_incomplete'](data)
            assert not story['_input_character_ids_incomplete'](data,n)
            StorySpec.model_validate(data)
        raw=json.loads(await complete(body(prompt('prompt/chat/CHOICES-TEMPLATE.md'))))
        found=[];choices['_accumulate'](found,set(),raw['choices'])
        assert len(found)==3
        data=json.loads(await complete(body(prompt('prompt/chat/JUDGEMENT-TEMPLATE.md'))))
        req=SimpleNamespace(main_events=[],endings=[],occurred_main_event_names=[])
        result=judgement['_sanitize'](req,data)
        assert result.target_main_event is None and result.ending_name is None
        schema=ModelDecision.model_json_schema()
        text=await complete(dict(model='gpt-6-luna',messages=[],response_format=dict(type='json_schema',json_schema=dict(schema=schema))))
        decision=moderation['parse_response'](SimpleNamespace(text=text,finish_reason='stop'),SimpleNamespace(paths={},images=[]))
        assert decision.decision=='APPROVED'
        fallback=await complete(body(prompt('prompt/moderation/MODERATION-TEMPLATE.md')))
        assert moderation['parse_response'](SimpleNamespace(text=fallback,finish_reason='stop'),SimpleNamespace(paths={},images=[])).decision=='APPROVED'

        # Execute real chat streaming parser with real LLM event types, generated wire frames.
        text=content(dict(stream=True),'chat')
        async def llm_stream(req):
            async for raw in stream_frames(text,req.model,fast):
                if raw==b'data: [DONE]\n\n':continue
                chunk=json.loads(raw.decode()[6:])
                if chunk['choices']:
                    delta=chunk['choices'][0]['delta'].get('content')
                    if delta:yield mod.TextDelta(delta)
            yield mod.StreamCompleted(model='deepseek-flash',provider='deepseek',usage=mod.TokenUsage(input_tokens=128,output_tokens=200),finish_reason='stop')
        chat_source=ast.parse(source('src/services/chat_llm.py'))
        names={n.name for n in chat_source.body if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef))}
        constants={t.id for n in chat_source.body if isinstance(n,ast.Assign) for t in n.targets if isinstance(t,ast.Name)}
        chat=functions('src/services/chat_llm.py',names,
                       dict(re=re,time=time,logging=logging,aclosing=aclosing,Iterable=Iterable,CharacterImageMapping=CharacterImageMapping,
                            EVENT_TOKEN=EVENT_TOKEN,EVENT_COMPLETED=EVENT_COMPLETED,EVENT_ERROR=EVENT_ERROR,EVENT_CHARACTER_IMAGE=EVENT_CHARACTER_IMAGE,
                            settings=SimpleNamespace(chat_model='deepseek-flash'),llm=SimpleNamespace(stream=llm_stream,provider_of=lambda _: 'deepseek'),
                            LlmRequest=mod.LlmRequest,TextDelta=mod.TextDelta,LlmError=mod.LlmError),constants)
        events=[e async for e in chat['stream_chat_turn']([],character_images=[CharacterImageMapping(name='동료',image_name='동료_기본',image_url='https://example.invalid/mock.webp')])]
        assert events[-1]['event']==EVENT_COMPLETED
        assert any(e['event']==EVENT_CHARACTER_IMAGE for e in events)
        assert '[[https://example.invalid/mock.webp]]' in events[-1]['ai_output']
        # Real image adapter's successful generation/edit response path. Only SDK
        # transport and telemetry contexts are stubbed; decoding/result code is real.
        image_base=types.ModuleType('ai_mock_image_base');sys.modules[image_base.__name__]=image_base
        exec(compile(source('src/services/image/base.py'),'image/base.py','exec'),image_base.__dict__)
        class Observation:
            def finish(self,**kwargs): pass
        class ImageClient:
            def __init__(self): self.images=self
            def with_options(self,**kwargs): return self
            async def generate(self,**kwargs): return await self.call(False,kwargs)
            async def edit(self,**kwargs): return await self.call(True,kwargs)
            async def call(self,edit,kwargs):
                kwargs.pop('timeout',None)
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(fast)),base_url='http://mock') as c:
                    if edit:
                        filename,payload,mime=kwargs.pop('image')
                        response=await c.post('/v1/images/edits',headers={'Authorization':'Bearer fake'},data=kwargs,files={'image':(filename,payload,mime)})
                    else:
                        response=await c.post('/v1/images/generations',headers={'Authorization':'Bearer fake'},json=kwargs)
                assert response.status_code==200
                return objectify(response.json())
        image_namespace=dict(image_base.__dict__) | dict(httpx=httpx,base64=base64,binascii=binascii,start_span=lambda *a,**k:nullcontext(),
            observe_generation=lambda *a,**k:nullcontext(Observation()),_client=lambda *a:ImageClient(),_OBSERVATION_NAMES={},_OUTPUT_FORMAT='webp')
        adapter=functions('src/services/image/openai_api.py',{'generate','_observation_name','_usage_details'},image_namespace)
        config_stub=types.ModuleType('src.core.config')
        config_stub.settings=SimpleNamespace(openai_api_key='fake',openai_api_url='http://mock/v1')
        previous=sys.modules.get('src.core.config')
        sys.modules['src.core.config']=config_stub
        try:
            for reference in (None,image_base.ImageReference(b'mock-image','image/png','mock.png')):
                image_request=image_base.ImageRequest(model='gpt-image-2.5-flare',prompt='mock',purpose='character',reference=reference)
                result=await adapter['generate'](image_request)
                assert result.image_bytes.startswith(b'RIFF') and result.provider=='openai'
        finally:
            if previous is None:sys.modules.pop('src.core.config',None)
            else:sys.modules['src.core.config']=previous
        for fmt in ('png','webp'):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(fast)),base_url='http://mock') as client:
                r=await client.post('/v1/images/generations',headers={'Authorization':'Bearer fake'},json=dict(model='image',output_format=fmt))
                image=base64.b64decode(r.json()['data'][0]['b64_json'],validate=True)
                assert image
        print('PASS: actual AI wire text/usage parsers, storylines, compile 0/1/5 characters + A/B names in real prompts/validators, choices, judgement, moderation strict/fallback, chat image label/parser and completed markers, real image generation/edit adapter and image base64')
    try:
        asyncio.run(checks())
    finally:
        sys.path.remove(snapshot.name)
        snapshot.cleanup()


if __name__=='__main__':
    main()
