# KNK-1596 부하 사용자와 k6

모든 도구는 이 디렉터리 안에서 독립적으로 실행합니다. 앱 코드와 Gradle을 변경하지 않습니다. 운영 스냅샷을 복원하고 외부 부작용을 차단한 **loadtest 환경**에서만 사용합니다. 실제 운영 URL이나 DB에 실행하지 않습니다.

## 준비와 시드

1. loadtest 정리 태스크로 복원본의 푸시 토큰·미발송 outbox 등을 먼저 정리합니다. 서버·AI가 실제 LLM 대신 `loadtest/mock-llm`을 호출하고 결제·푸시·실제 외부 알림이 차단됐는지 확인합니다.
2. loadtest psql 태스크 또는 그 DB에 접근 가능한 일회성 클라이언트에서 아래 시드를 실행합니다. `PGUSER`·`PGPASSWORD`는 loadtest RDS 관리 시크릿으로 주입하고 셸 추적을 켜지 않습니다. DB 이름은 운영과 같은 `manyak`이므로 DB 이름만으로 안전성을 판단하지 않습니다.

```sh
cd loadtest/k6
# 실제 loadtest RDS endpoint를 두 변수에 지정합니다. PGUSER/PGPASSWORD는 안전한 환경 주입을 사용합니다.
export PGHOST=YOUR_LOADTEST_RDS_ENDPOINT
export EXPECTED_LOADTEST_HOST="$PGHOST" PGDATABASE=manyak LOADTEST_SEED_CONFIRM=yes
sh seed-db.sh -v user_count=500 -v credits=1000000 -v terms_version=v1.4 -v privacy_version=v1.7
```

`seed-db.sh`는 host 완전 일치와 `manyak-loadtest-pg.*.rds.amazonaws.com` 패턴, 명시 확인을 검사합니다. SQL은 정리 SQL과 같은 `manyak.loadtest_guard=manyak-loadtest-pg` 세션 가드를 검사합니다. 세션 가드는 단독으로 실제 DB 신원을 증명하지 않으므로 wrapper의 endpoint 검사도 함께 사용합니다. 로컬 DB에서 시드를 검증하려면 격리된 로컬 DB에만 세션 가드를 직접 지정하고 `psql -X -f seed.sql`을 사용합니다.

회원 UUID는 `15960000-0000-4000-8000-`와 12자리 번호, 닉네임은 `k6loadtest`와 6자리 번호입니다. ACTIVE 회원과 현행 TERMS·PRIVACY·AGE14 동의를 생성합니다. 시드 변수의 문서 버전을 서버의 MANYAK_LEGAL_TERMS_VERSION·MANYAK_LEGAL_PRIVACY_VERSION과 맞춥니다. 기본은 application.yml의 v1.4·v1.7, AGE14는 코드의 `1`입니다.

V16(users), V24(wallet/transactions), V39(lots), V40(member_trial_seeded_at), V84(consents)를 기준으로 만들었습니다. access 인증은 users의 상태·동의를 확인하며 social_accounts나 refresh family를 필요로 하지 않습니다. 회원 체험 로트의 추가 자동 적립을 피하도록 member_trial_seeded_at을 채웁니다. 적립은 무기한 PURCHASE 원장과 같은 금액의 로트, 지갑 증가를 하나의 트랜잭션에서 수행합니다. 재실행은 멱등 키·ON CONFLICT로 중복을 막으며 소비한 잔액을 복구하지 않습니다. 크레딧이 부족해지면 새 복원본 또는 새 시드 설계를 사용합니다. 테스트 회원이 생성한 채팅은 실행 간 남습니다.

## 토큰과 스토리

```sh
# MANYAK_AUTH_JWT_SECRET은 loadtest 서버와 동일한 값을 안전하게 환경 주입합니다. 평문 명령에 쓰지 않습니다.
python3 gen-tokens.py --count 500 --ttl 14400 --out tokens.json
# official_user_public_id는 서버 MANYAK_OFFICIAL_USER_PUBLIC_ID와 같은 공개 UUID입니다.
psql -X -At --no-password -v official_user_public_id=YOUR_OFFICIAL_PUBLIC_UUID -f stories.sql > stories.json
```

토큰은 JwtTokenProvider와 같은 UTF-8 대칭키 HS256 및 `iss=manyak`, `sub=회원 public_id`, `iat`, `exp`를 사용합니다. 키는 최소 32바이트입니다. TTL은 전체 측정·기동 시간을 덮도록 지정합니다. refresh 토큰·세션은 생성하지 않습니다. 토큰 파일은 0600이며 git·Docker context에서 제외됩니다. 시드 수와 토큰 수가 같아야 하며 최대 VU 수 이상의 서로 다른 회원을 준비합니다.

오리지널은 DB enum이 아니라 공식 계정 소유 여부입니다. `stories.sql`은 그 계정의 PUBLISHED·PUBLIC·미삭제 UUID만 추출합니다. STORIES_FILE을 생략하면 setup에서 `GET /stories?filter=original` 응답의 `items[].id`를 사용합니다. 목록이 비어 있으면 중단합니다. 스토리 상세 UUID는 이 목록에서 순환 선택하며 회원마다 자기 채팅을 만듭니다.

## 실행

```sh
# 설치된 k6로 정적 검사합니다.
k6 inspect run.js
k6 inspect scenarios/chat.js
# 로컬 스택을 먼저 기동한 후 실행합니다.
BASE_URL=http://localhost:8080 TOKENS_FILE=./tokens.json STORIES_FILE=./stories.json \
  PROFILE=smoke SCENARIO=chat DURATION=30s VUS=1 k6 run run.js
BASE_URL=http://localhost:8080 PROFILE=smoke SCENARIO=browse k6 run run.js
# VPC 안의 internal HTTP ALB를 대상으로 실행합니다.
BASE_URL=http://YOUR_INTERNAL_ALB TOKENS_FILE=./tokens.json PROFILE=ramp SCENARIO=mixed k6 run run.js
```

`scenarios/browse.js`, `member-read.js`, `chat.js`, `search.js`, `mixed.js`를 직접 실행해도 됩니다. 결과 경로는 SUMMARY_FILE로 지정하고 해당 디렉터리를 미리 만듭니다. 기본은 `/tmp/k6-summary.json`입니다.

| 변수 | 기본값 | 의미 |
| --- | --- | --- |
| BASE_URL | http://localhost:8080 | /api/v1 이전의 URL |
| SCENARIO | mixed | browse / member-read / chat / search / mixed |
| PROFILE | smoke | smoke / ramp / spike / soak |
| TOKENS_FILE | 없음 | 회원 publicId·token JSON 배열 |
| STORIES_FILE | 없음 | 오리지널 공개 UUID JSON 배열 |
| VUS | smoke 1, soak 50, spike 200 | 고정 또는 spike 최고 VU |
| HOLD_TIME | 4m | ramp 각 단계·spike 유지 |
| RAMP_TIME | 30s | ramp 단계 간 전환 |
| DURATION | smoke 30s, soak 2h | 고정 VU 유지 |
| THINK_TIME | chat 2, 나머지 1 | 반복 사이 초 |
| HTTP_TIMEOUT / SETUP_TIMEOUT | 120s / 15m | 요청·setup 제한 |
| SEARCH_QUERY | 아카데미 | 검색어, 서버 계약상 2~100자 |
| USER_INPUT | 고정 합성 입력 | 턴 입력 |
| REALTIME_IMAGE | false | 기본 이미지 생성 제외 |
| MOCK_CHAT_SECONDS | 10.4 | 스트림 한 턴의 전체 mock 예산(초) |
| SUMMARY_FILE | /tmp/k6-summary.json | 로컬 결과 경로 |

ramp는 10→25→50→100→200 VU를 각 4분 유지하고 전환 구간을 별도로 기록합니다. spike는 0→N 10초, 유지, 10초 하강입니다. soak는 고정 VU입니다. `ramping-vus`를 사용하므로 느린 SSE가 동시 사용자를 점유합니다. 고정 도착률·RPS를 보장하는 테스트가 아니며 서버가 느려지면 처리량이 감소하는 closed model입니다. 종료 시 SSE 완료를 위해 2분 유예합니다.

browse와 member-read는 해당 GET 비율 안에서 정규화합니다. mixed는 지시한 16개 가중치 합 **90.8**로 나눕니다. 누락된 9.2% 경로를 임의로 만들지 않습니다. create 2.0, turn 3.2, choices 2.9는 각각 별도 요청 선택입니다. choices는 새 채팅을 만든 직후에도 마지막으로 완료된 채팅·턴을 사용하므로 요청 종류를 다른 종류로 대체하지 않습니다. 유한 표본의 최종 실현 비율은 summary의 요청 건수로 확인합니다. 이미 생성된 선택지의 재조회는 서버 캐시 경로가 되며 반복 캐시 호출의 비율도 실제 성능 해석에 반영합니다.

회원 VU는 최초 반복에서 /auth/me, 채팅 생성으로 자기 fixture를 준비하고 mixed는 완료 턴 하나도 준비합니다. 준비 요청은 phase=setup이며 사용자 정의 latency/error 통계에서 제외됩니다. 이 준비가 첫 부하 구간의 시간을 소비하므로 최초 구간은 warm-up 영향이 있고 충분한 hold 시간을 둡니다. 준비 실패는 실행을 중단합니다. chat은 매 반복 create → stream → 완료 이벤트의 turnId로 choices 순서이며 think time은 사이클 사이에 둡니다. 장시간 mixed 채팅의 엔딩·빈 응답·402도 성공으로 숨기지 않고 실패로 계산합니다.

## 지표와 결과

HTTP 상태(POST /chats 201, 나머지 200)와 최소 JSON/SSE 계약을 검사합니다. SSE는 HTTP 200이어도 error 이벤트나 completed 누락이면 실패입니다. 포트·UUID·검색어 대신 모든 요청에 템플릿 `name` 태그를 붙이고 device/session 헤더를 보냅니다. 이 헤더는 RequestCorrelationFilter에서 추적용이며 누락 거부는 없지만 게스트 경로 일부는 device를 필요로 합니다.

전체 요청 에러율 <1%, 읽기 p95 <500ms는 실패 임계값입니다. `chat_first_byte`는 k6 `timings.waiting`이므로 첫 HTTP 바이트이지 첫 token 이벤트 시각은 아닙니다. `chat_full_time`은 전체 SSE 수신시간입니다. `chat_server_overhead`는 전체 시간에서 MOCK_CHAT_SECONDS를 뺀 값이며 p95 <1000ms는 보고 목표만 제공합니다. 기본 10.4는 mock stream 7.5 + 판정 non-stream 2.9의 합입니다. 실제 모델 호출 개수·동시 호출·이미지 설정에 맞게 예산을 바꿉니다. 음수도 그대로 남겨 잘못된 예산을 발견할 수 있게 합니다. 네트워크·AI 처리도 포함하므로 순수 서버 CPU 지연으로 해석하지 않습니다.

handleSummary는 전환·유지 단계마다 엔드포인트별 min/avg/p95/p99/max(ms), 요청 수·에러율, SSE 지표와 임계값 결과를 JSON으로 저장하고 stdout의 K6_SUMMARY_JSON 뒤에도 출력합니다. CloudWatch 로그로 회수할 수 있습니다. 결과가 없는 셀은 생략하고 요청이 없는 단계는 errorRate=null입니다. stage는 요청 시작 시각으로 정합니다. bootstrap은 사용자 정의 지표에서 제외되지만 k6 기본 HTTP 지표에는 포함됩니다.

구현 기준: [k6 custom summary](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/), [ramping-vus](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-vus/), [metrics](https://grafana.com/docs/k6/latest/using-k6/metrics/).

## Fargate 일회성 태스크

아래 Docker 명령은 레포 루트에서 실행합니다.

```sh
docker build -t manyak-k6 loadtest/k6
# 로컬 토큰을 이미지에 넣지 않고 read-only mount합니다.
docker run --rm --mount type=bind,src="$PWD/loadtest/k6/tokens.json",dst=/data/tokens.json,readonly \
  -e TOKENS_FILE=/data/tokens.json -e BASE_URL=http://host.docker.internal:8080 \
  -e PROFILE=smoke -e SCENARIO=chat manyak-k6
```

Fargate는 기존 loadtest cluster와 app subnet, `client_security_group_id`를 사용합니다. task 정의에 CPU·메모리와 awslogs를 설정하고 BASE_URL은 `api_url` output의 internal ALB HTTP를 사용합니다. 서비스 이름은 services output로 확인합니다. 외부 공개 ALB나 운영 시크릿을 사용하지 않습니다. 이미지는 grafana/k6:1.3.0 기반이며 표준 라이브러리 토큰 생성용 Python만 추가합니다.

ECS `secrets`에 loadtest app secret의 JWT 키를 MANYAK_AUTH_JWT_SECRET으로 넣습니다. execution role에 해당 loadtest 시크릿 GetSecretValue 권한이 필요합니다. USER_COUNT(기본500), TOKEN_TTL(기본14400), JWT_ISSUER(기본manyak), PROFILE·SCENARIO를 task environment로 넣습니다. entrypoint는 `/tmp/tokens.json`을 0600으로 만든 뒤 JWT 키 환경변수를 지우고 k6를 실행합니다. 키·토큰은 이미지·stdout에 넣지 않습니다. Fargate 결과는 task의 CloudWatch 로그를 회수합니다. 이 작업은 task 정의 등록·ECR push·실제 실행을 수행하지 않습니다.

## 검증과 남은 실행

```sh
node --experimental-vm-modules --test loadtest/k6/tests/*.test.js
python3 -m unittest discover -s loadtest/k6/tests -p 'test_*.py'
# Dockerfile build, k6 inspect, 위 local smoke, 격리 PostgreSQL seed 2회 실행이 후속 검증입니다.
```

JS 테스트는 SSE 오류·가중치·프로필 경계와 실제 entrypoint의 요청 순서·헤더·turnId·요약을 k6 대체 호스트로 확인합니다. JWT 테스트는 서명과 클레임을 확인합니다. 이 검증은 실제 k6 런타임이나 서버·DB 통합을 대체하지 않습니다. 현재 샌드박스에는 k6·psql이 없고 DNS 제한으로 설치할 수 없어 실제 inspect·smoke·시드 실행을 하지 못했습니다. 지정 Terraform origin/dev에는 loadtest 경로가 없어 준비된 `/tmp/knk-1440/tf-1594/terraform/envs/loadtest`의 cleanup-task·cleanup.sql·outputs를 읽기만 했습니다. mock-llm도 현재 워크트리에 없어 `/tmp/knk-1440/srv-1595/loadtest/mock-llm`을 참조했습니다. 머지 후 최종 환경과 다시 대조합니다.
