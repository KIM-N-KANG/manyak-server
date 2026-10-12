"""Only schema-less manyak prompts need purpose-specific wire fixtures."""
import json
import re

CHOICES = ['주변을 살펴본다.', '동료에게 말을 건다.', '앞으로 걸어간다.']
JUDGEMENT = {'target_main_event': None, 'occurred_main_event_name': None, 'ending_name': None}


def prompt_text(body, role=None):
    # Never return this text to logs or error messages.
    return '\n'.join(m['content'] for m in body.get('messages', []) if isinstance(m, dict) and isinstance(m.get('content'), str) and (role is None or m.get('role') == role))


def purpose(body):
    rf = body.get('response_format', {})
    if rf.get('type') == 'json_schema':
        return 'schema'
    if body.get('stream'):
        return 'chat'
    text = prompt_text(body, 'system')
    for key, kind in [('prompt_settings', 'compile'), ('recommended_infos', 'storylines'), ('choices', 'choices'), ('target_main_event', 'judgement'), ('APPROVED', 'moderation')]:
        if key in text:
            return kind
    return 'text'


def characters(body):
    text = prompt_text(body, 'user')
    cards = []
    for identity, name, gender in re.findall(r'\[input_character_id: (input-\d+)\]\s*이름:\s*([^\n]+?) / 성별:\s*([^\n]+?) / 특징:', text):
        cards.append((identity, name.strip(), gender.strip()))
    if not cards:
        cards = [('input-1', '동료', '여성')]
    reserved = {name for _,name,_ in cards if name != '(미정)'}
    reserved.update(re.findall(r'주인공:\s*이름:\s*([^\n]+?) / 성별:', text))
    result = []
    for i,(identity,name,gender) in enumerate(cards):
        if name == '(미정)':
            serial = i+1
            name = f'동료{serial}'
            while name in reserved:
                serial += 1
                name = f'동료{serial}'
        reserved.add(name)
        result.append(dict(name=name, gender=gender if gender != '(미정)' else '여성', input_character_id=identity,
                           description='함께 길을 찾는 동료', personality='침착함', tone='차분한 말투', motivation='안전하게 돌아가기', attitude_to_user='신뢰함',
                           age='성인', body='보통 체격', face='부드러운 인상', hair='검은 머리', outfit='여행복', visual_identity='파란 망토'))
    return result


def compile_fixture(body):
    return dict(meta=dict(title='부하 테스트 스토리', one_line_intro='동료와 길을 찾습니다.', description='안전한 가짜 스토리입니다.', genre='판타지'),
                prompt_settings=dict(world_setting='작은 마을과 숲', plot_setting=dict(premise='길을 잃었다.', conflict='돌아갈 길을 찾는다.'),
                                     rule_setting='서로 돕는다.', tone_setting='차분함', length_ratio='지문과 대사 균형', character_setting=characters(body),
                                     user_role_setting=dict(name='여행자', gender='여성', role='탐험가', background='마을에서 출발함', personality='신중함', preference='안전한 길')),
                start=dict(name='출발', prologue='마을 밖으로 걸어 나왔다.', start_situation='숲 입구에 서 있다.'),
                suggested_inputs=CHOICES,
                main_events=[dict(name=f'사건 {i}', description='동료와 함께 길을 찾는다.', key_sentence='앞으로 나아간다.') for i in range(1,4)],
                endings=[dict(name=f'엔딩 {i}', min_turns=1, achievement_condition='목적지에 도착한다.', epilogue='마을에 돌아왔다.') for i in range(1,4)])


def content(body, kind):
    if kind == 'compile':
        result = compile_fixture(body)
    elif kind == 'storylines':
        # Actual prompts render each named supporting character as "이름: ... /".
        names = re.findall(r'^(?:\d+\)\s*)?이름:\s*([^\n]+?) / 성별:', prompt_text(body, 'user'), re.M)
        cast = ', '.join(n.strip() for n in names if n.strip() != '(미정)') or '동료'
        result = dict(stories=[dict(id=i, storyline=f'{cast}와 숲에서 길을 찾다가 낯선 발자국을 발견했다. 함께 앞으로 나아갈지 결정한다. {i}', recommended_infos=CHOICES) for i in range(1,4)])
    elif kind == 'choices':
        result = dict(choices=CHOICES)
    elif kind == 'judgement':
        result = JUDGEMENT
    elif kind == 'moderation':
        result = dict(decision='APPROVED', issues=[])
    elif body.get('response_format', {}).get('type') == 'json_object':
        raise ValueError('unknown schema-less purpose')
    else:
        return '바람이 숲을 지나갔다. 동료가 앞을 가리켰다.\n동료: 함께 길을 찾아보자.\n천천히 발걸음을 옮겼다.\n' * 12
    return json.dumps(result, ensure_ascii=False)
