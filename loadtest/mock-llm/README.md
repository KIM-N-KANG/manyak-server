# 부하 테스트용 가짜 LLM

[KNK-1595](https://kimandkang.atlassian.net/browse/KNK-1595)의 독립 Python 서버입니다. 서버 앱 코드와 Gradle 빌드를 사용하지 않습니다. Starlette와 uvicorn으로 비동기 응답을 제공하며, 실제 공급자를 호출하거나 프록시하지 않습니다. Authorization은 비어 있지 않은 Bearer 키만 확인합니다. 키·프롬프트를 기록하지 않으며 실행 명령에서 access log를 끕니다.

## 실행과 이미지

```bash
cd loadtest/mock-llm
python3.12 -m venv .venv
.venv/bin/pip install -r requirements-test.txt
.venv/bin/uvicorn app:create_app --factory --host 0.0.0.0 --port 8000 --no-access-log --log-level warning
```

```bash
docker build -t manyak-mock-llm loadtest/mock-llm
docker run --rm -p 8000:8000 manyak-mock-llm
# 두 플랫폼 배포용 이미지는 승인된 레지스트리를 지정해 별도 게시합니다.
docker buildx build --platform linux/amd64,linux/arm64 -t YOUR_REGISTRY/manyak-mock-llm:TAG --output type=oci,dest=/tmp/mock-llm.tar loadtest/mock-llm
```

Dockerfile은 Python 3.12 slim, UID/GID 10001, 포트 8000, `/health` 헬스 검사입니다. 플랫폼별 바이너리를 복사하지 않으며 pip가 대상 플랫폼 의존성을 설치합니다. 실제 이미지 빌드와 플랫폼별 실행 검증은 필요합니다.

AI의 `DEEPSEEK_API_URL`, `OPENAI_API_URL`을 `http://mock-llm.<namespace>:8000/v1`로 설정하고 두 키에는 가짜 값을 사용합니다. Gemini·Anthropic 전용 SDK 프로토콜은 이 서버가 제공하지 않습니다. loadtest의 CHAT_MODEL, CHAT_CHOICE_MODEL, STORYLINES_MODEL, STORY_COMPILE_MODEL, MODERATION_MODEL, MODERATION_FALLBACK_MODEL은 DeepSeek/OpenAI 등록 모델을 사용합니다. 이미지 생성은 OpenAI 이미지 모델을 사용합니다. 모델 이름만 Gemini로 바꾸면 OpenAI URL을 거치지 않으므로 기동 전에 모델과 빈 공급자 키를 확인합니다.

## API와 응답

- `GET /health`: 인증 없이 `{"status":"ok"}`를 반환합니다.
- `POST /v1/chat/completions`: 일반 ChatCompletion 또는 SSE ChatCompletionChunk를 반환합니다. SSE는 assistant/content delta, stop 청크, choices가 빈 usage 청크, `[DONE]` 순서입니다. usage는 실제 토크나이저 계산값이 아닌 합성 값입니다.
- `response_format.type=json_schema`: 요청 스키마에서 값을 생성한 뒤 jsonschema로 검증합니다. 로컬 `$ref`, enum/const, nullable/union, object/array, 길이·수치 경계, 기본 정규식 및 allOf를 지원합니다. 재귀·원격 참조·복잡한 제약 등 생성하지 못하는 스키마는 고정 문구의 400으로 거부합니다. 모든 validator에 외부 조회를 거부하는 Registry를 지정하며, `$dynamicRef`·`$recursiveRef`는 로컬 값도 지원하지 않아 생성 전에 거부합니다. 원격 스키마를 가져오지 않습니다. tools는 현재 AI 호출에 없으며 전달하면 400입니다.
- 스키마 없는 `json_object`: system 프롬프트의 구조 키로 스토리라인·컴파일·선택지·판정·검수를 구분합니다. 알려지지 않은 용도는 임의 `{}` 대신 400입니다. 프롬프트 구조가 바뀌면 검증 스크립트를 다시 실행합니다.
- `POST /v1/images/generations`: 작은 PNG를 base64로 반환합니다. AI가 `output_format=webp`를 요청하면 고정 작은 WebP를 반환합니다. `n=1`만 지원합니다.
- `POST /v1/images/edits`: `image` 또는 `image[]` 파일이 있는 multipart 요청을 받으며 생성과 같은 응답을 반환합니다. 이미지 내용에 따른 실제 편집은 하지 않습니다.

채팅은 고정 합성 본문과 `동료:` 화자 라벨을 사용합니다. AI가 이미지 매핑 이름과 라벨을 대조해 이미지 이벤트와 `[[URL]]` 저장 마커를 만듭니다. LLM이 `[character:이름]` 등의 옛 태그를 반환하는 방식은 현재 코드에 없습니다. 이미지 시나리오는 기본 측정에서 제외하며, 매핑을 시험할 때는 테스트 인물 이름을 `동료`로 맞춥니다. 출력 내용·토큰량·이미지 크기는 품질 평가나 실제 공급자 비용 측정에 사용할 수 없습니다.

## 지연과 장애 주입

| 환경 변수 | 기본값 | 동작 |
| --- | --- | --- |
| MOCK_FIRST_TOKEN_SECONDS | 0.3 | 첫 content 청크까지 지연 |
| MOCK_STREAM_TOTAL_SECONDS | 7.5 | 첫 요청 처리 뒤 마지막 content 청크까지 시간, 첫 토큰 이상이어야 합니다 |
| MOCK_STREAM_CHUNKS | 60 | content 청크 목표 수, 2~4096, 짧은 본문은 문자 수로 제한합니다 |
| MOCK_COMPLETE_SECONDS | 2.9 | 비스트림 기본 지연, 판정 실측값 기준 |
| MOCK_MODEL_DELAYS_JSON | `{}` | 모델별 비스트림 초 단위 지연, 예: `{"gpt-5.6-terra":10}` |
| MOCK_PURPOSE_DELAYS_JSON | `{}` | 용도별 비스트림 지연, 키: text, schema, compile, storylines, choices, judgement, moderation |
| MOCK_IMAGE_SECONDS | 2.9 | 이미지 지연, 이미지 실측값이 아닌 초기 설정값입니다 |
| MOCK_EXTRA_DELAY_SECONDS | 0 | 추가 지연, 스트림 첫 토큰·마지막 토큰 모두에 더합니다 |
| MOCK_ERROR_RATE | 0 | 요청별 500/429 발생 확률, 0~1 |
| MOCK_ERROR_429_SHARE | 0.5 | 오류 중 429 비율, 나머지는 500 |
| MOCK_STREAM_DISCONNECT_RATE | 0 | 스트림 중간 전송 중단 확률, 0~1 |

비스트림은 모델별 설정, 용도별 설정, 기본값 순으로 선택합니다. 추가 지연은 선택한 값에 더합니다. 스트림은 전체 시간 안에 content 청크를 균등 배치하고 `asyncio.sleep`으로 대기합니다. 정규식·스키마 합성은 요청 전 계산하므로 스트림 동안 요청별 작업은 작은 JSON 청크 전송입니다. 장애 주입은 [KNK-1556](https://kimandkang.atlassian.net/browse/KNK-1556)의 지연·500/429·중간 끊김 실험에 사용합니다. 끊김은 usage/stop/DONE 없이 HTTP 전송 오류로 종료합니다. uvicorn은 고정된 전송 오류 stack을 기록할 수 있으나 요청 본문과 키는 기록하지 않습니다. SDK 재시도가 있을 수 있으므로 실제 AI의 전체 지연·실패율은 설정 확률과 다를 수 있습니다.

기본 채팅·판정 지연 외에 스토리라인·컴파일·선택지·검수·이미지에는 독립 실측값을 넣어야 합니다. 이 서버는 실제 공급자의 부하별 큐잉, 토큰 추론, 비용을 재현하지 않습니다. 수백 연결의 실제 Docker/ECS 처리량은 배포 후 CPU·메모리·event loop와 함께 확인합니다.

## 테스트

```bash
cd loadtest/mock-llm
.venv/bin/python -m pytest -q
# 같은 테스트는 unittest runner로도 실행할 수 있습니다.
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -B validate_ai.py --ai-root /Users/kangkyunghyun/Projects/knk-workspace/manyak-ai --revision origin/dev
```

검증 스크립트는 `git show`로 선택 리비전의 AI 코드만 읽고 임시 디렉터리에 스키마를 가져와 sys.path로 참조합니다. AI 레포·git·환경 파일을 변경하지 않으며 bytecode도 쓰지 않습니다. SDK·관측 기동을 피하려고 실제 소스 AST에서 순수 파서·검증 함수와 채팅 스트림 함수를 추출해 실행합니다. OpenAI wire 본문·usage, 스토리라인, 컴파일 필수값·인물 0/1/5명·StorySpec, 선택지, 판정, strict/fallback 검수, 화자 이미지 이벤트·저장 마커, 이미지 생성·편집 어댑터의 응답 처리를 검사합니다. SDK 전송과 관측 컨텍스트는 모의 처리하므로 실제 SDK HTTP 통합·업로드·백엔드 연동의 검증은 별도로 필요합니다.

구현은 [Starlette StreamingResponse](https://www.starlette.io/responses/#streamingresponse), [jsonschema 검증](https://python-jsonschema.readthedocs.io/en/stable/validate/) 동작을 사용합니다.
