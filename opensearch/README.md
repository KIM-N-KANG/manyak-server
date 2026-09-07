# OpenSearch 로그 인덱스

manyak 애플리케이션 로그를 OpenSearch에 적재하기 위한 자산입니다. 로컬 학습 스택(`docker-compose.observability.yml`)과 운영 도메인이 **같은 파일**을 씁니다.

| 파일 | 용도 |
|---|---|
| `setup.sh` | 스택 기동 후 한 번 실행하는 초기 설정 |
| `index-template.json` | 인덱스 템플릿(필드 타입 정의) |
| `fluent-bit.conf` | 로그 수집·파싱·전송 설정 |
| `parsers.conf` | 위에서 쓰는 JSON 파서 정의 |
| `vector.yaml` | 중앙 가공·버퍼 계층 설정 |

## 시작하기

```bash
docker compose -f docker-compose.observability.yml up -d
./opensearch/setup.sh
```

### 앱 로그를 흘려보기

평소 개발은 종전대로 `./gradlew bootRun`을 쓰고, 로그 파이프라인을 볼 때만 앱을 컨테이너로 띄웁니다.

```bash
./gradlew bootJar
docker compose -f docker-compose.observability.yml --profile app up -d app
```

앱은 `localhost:18080`에 뜹니다. 요청을 하나 보내고 `request_id`로 찾아보면 파이프라인 전체가 확인됩니다.

```bash
curl -s -o /dev/null -H 'X-Manyak-Request-Id: req_demo_0001' \
  -H 'X-Manyak-Device-Id: d' -H 'X-Manyak-Session-Id: s' \
  http://localhost:18080/api/v1/stories/simple/tags
curl -s "http://localhost:9200/manyak-logs-local-*/_search?q=request_id:req_demo_0001&pretty"
```

경로는 이렇습니다. 앞쪽 절반이 운영(ECS FireLens)과 같은 모양이라, 여기서 검증한 파싱·전송 설정이 [KNK-855](https://kimandkang.atlassian.net/browse/KNK-855)로 그대로 넘어갑니다.

```
앱 stdout → 도커 fluentd 드라이버 → Fluent Bit → Vector → OpenSearch
                                    (수집)      (가공·버퍼)
```

## 왜 Vector를 한 겹 더 두나

Fluent Bit만으로도 OpenSearch에 넣을 수 있습니다. 그런데 **목적지가 잠깐 죽으면 로그가 사라집니다.** 로컬에서 두 번 재현해 확인했습니다.

| 구성 | 실험 | 결과 |
|---|---|---|
| Fluent Bit → OpenSearch | Fluent Bit을 1분 중단 | 그 사이 로그 **유실** |
| Fluent Bit → **Vector** → OpenSearch | OpenSearch를 중단 | 버퍼에 쌓였다가 **복구 후 전달** |

도커 로그 드라이버의 `fluentd-async` 버퍼는 메모리뿐이라 한도를 넘으면 버립니다. Vector의 디스크 버퍼(`buffer.type: disk`)가 그 구멍을 메웁니다. 운영(Fargate FireLens)도 디스크 버퍼가 사실상 없어 같은 위험을 안습니다.

Vector가 맡는 나머지 일은 **가공**입니다. `vector.yaml`의 VRL이 `container_name`의 앞 슬래시를 떼어 운영과 모양을 맞추고, 전송 과정에서 딸려온 찌꺼기(`timestamp`·`path`·`source_type`)를 걷어냅니다. 이런 규칙을 중앙 한 곳에서 고칠 수 있다는 게 계층을 나누는 이유입니다.

### Vector와 Data Prepper

같은 자리(수집기와 저장소 사이의 가공 계층)를 놓고 겨루는 도구라 **둘 중 하나만** 씁니다. 이 스택은 Vector를 골랐습니다.

| | Vector | Data Prepper |
|---|---|---|
| 만든 곳 | Datadog(Timber.io 인수) | OpenSearch 프로젝트 |
| 언어 | Rust | Java |
| 로그 | 가볍고 VRL로 변환이 자유롭다 | 되지만 무겁다 |
| 트레이스 | **서비스 맵을 만들 수 없다** | `service_map_stateful` 전용 프로세서 |
| 목적지 | OpenSearch·S3·Kafka·CloudWatch 등 다수 | OpenSearch 중심 |

**로그만 놓고 보면 Vector가 낫습니다.** 메모리를 적게 쓰고, VRL이 Data Prepper의 프로세서 조합보다 표현력이 좋으며, 디스크 버퍼가 제대로 동작합니다.

**트레이스는 얘기가 다릅니다.** OpenSearch Dashboards의 Trace Analytics는 `otel-v1-apm-span-*`과 `otel-v1-apm-service-map` 인덱스를 읽는데, 뒤쪽을 만드는 `service_map_stateful` 프로세서가 Data Prepper에만 있습니다. Vector에는 대응물이 없습니다. 그래서 트레이스를 붙일 때는 **로그는 Vector, 트레이스는 Data Prepper**로 두 경로를 따로 두게 됩니다. 둘 중 하나를 고르는 문제가 아닙니다.

트레이스는 이 스택의 범위 밖이라 Data Prepper는 두지 않았습니다. 필요해지는 시점은 로그만으로 장애 원인을 못 좁힐 때입니다.

### Fluent Bit → Vector는 forward가 아니라 HTTP입니다

`forward`(fluentd 프로토콜)로 보내면 Vector가 통째로 버립니다.

```
Error decoding fluent message. error=UnexpectedValue(...) error_type="parser_failed"
```

Fluent Bit 5.x는 각 항목을 `[[시각, 메타데이터], 레코드]`인 **v2 이벤트 형식**으로 보내는데 Vector의 `fluent` 소스가 이를 해석하지 못합니다. 데이터는 도착하는데 색인만 안 되므로 원인을 찾기 어렵습니다. HTTP + NDJSON으로 보내면 이 문제가 없습니다.

#### 알아둘 것

- **`profiles: ["app"]`이라 `--profile app` 없이는 앱이 뜨지 않습니다.** 평소 `up -d`는 관측 스택만 띄웁니다.
- **`.env`의 빈 값이 yml 기본값을 덮습니다.** `MANYAK_AUTH_JWT_SECRET`이 빈 값이라 그대로 두면 `IllegalArgumentException: Empty key`로 죽습니다. compose에서 `${...:-기본값}`으로 막아뒀습니다. `bootRun`은 `.env`를 읽지 않아 이 문제가 드러나지 않습니다.
- **파싱에 실패한 줄도 버려지지 않습니다.** Spring 배너처럼 JSON이 아닌 줄은 `log` 필드를 단 채 통과합니다(기동당 20줄 남짓). 관측 시스템에서 유실이 최악이라 파서를 느슨하게 둔 것입니다.
- **Docker Desktop은 이중 로깅을 해서** `fluentd` 드라이버를 써도 `docker logs`가 됩니다. 운영 ECS에는 없는 편의라, FireLens를 쓰면 그 경로 조회가 막힙니다.

`setup.sh`는 **사는 곳이 다른 두 가지**를 등록합니다. 이름이 비슷해 헷갈리기 쉽습니다.

| | 무엇을 정하나 | 어디에 저장되나 |
|---|---|---|
| **인덱스 템플릿** | 로그를 어떤 **타입으로 저장**할지 | OpenSearch(9200) |
| **인덱스 패턴** | Discover에서 어떤 인덱스를 **어떤 시간축으로 볼지** | Dashboards(5601) |

둘 다 컨테이너 안에만 삽니다. `down -v`로 볼륨을 지우면 함께 사라지므로, 그때는 `setup.sh`를 다시 실행하면 됩니다. 여러 번 실행해도 안전합니다.

### 스크립트가 대신 피해 주는 함정

- **인덱스 패턴에 필드 목록을 함께 넣어야 합니다.** `title`·`timeFieldName`만 등록하면 Discover가 `Could not locate that index-pattern-field (id: @timestamp)`로 막힙니다.
- **필드 목록은 실제 인덱스가 있어야 읽을 수 있습니다.** 인덱스가 하나도 없으면 400이 납니다. 그래서 빈 인덱스(`manyak-logs-local-{날짜}`)를 먼저 만듭니다.
- **저장 객체 생성은 `POST ?overwrite=true`입니다.** `PUT`은 기존 객체 수정 전용이라 처음 실행에서 404가 납니다.

## 인덱스 이름 규칙

```
manyak-logs-{환경}-{YYYY.MM.DD}
예) manyak-logs-dev-2026.08.19, manyak-logs-prod-2026.08.19
```

템플릿의 `index_patterns`가 `manyak-logs-*`라 두 환경 모두 같은 매핑을 받습니다. 날짜로 인덱스를 나누는 이유는 보관 정책(ISM)을 날짜 단위로 걸어 오래된 인덱스를 통째로 지우기 위해서입니다. 문서를 개별 삭제하는 것보다 훨씬 쌉니다.

## 적용

```bash
curl -X PUT "http://localhost:9200/_index_template/manyak-logs" \
  -H 'Content-Type: application/json' -d @opensearch/index-template.json
```

**인덱스가 만들어지기 전에** 등록해야 합니다. 템플릿은 인덱스 생성 시점에만 적용되며, 이미 존재하는 인덱스의 매핑은 바꾸지 않습니다.

## 필드

로그 스키마의 정본은 `src/main/resources/logback-spring.xml`의 LogstashEncoder 출력입니다. 아래는 dev 환경(`/ecs/manyak-dev`) 실제 로그를 전수 조사해 확정한 목록입니다.

**항상 있는 필드** — LogstashEncoder 기본

| 필드 | 타입 | 비고 |
|---|---|---|
| `@timestamp` | date | 나노초 9자리(`...454389442Z`)로 오지만 `date`가 파싱해 밀리초로 절삭합니다 |
| `@version` | keyword | 상수 `"1"` |
| `message` | text | 전문 검색 대상 |
| `level` | keyword | INFO·WARN·ERROR |
| `level_value` | integer | 레벨의 수치 표현. 범위 검색용 |
| `logger_name` | keyword | 집계 대상 |
| `thread_name` | keyword | |
| `service` | keyword | `manyak-server` / `manyak-ai` |

**조건부 필드**

| 필드 | 타입 | 언제 |
|---|---|---|
| `tags` | keyword | 로거 마커가 있을 때 (예: `["COMMONS-LOGGING"]`) |
| `stack_trace` | text | 예외가 실릴 때 |
| `request_id`·`session_id`·`device_id_hash` | keyword | MDC(`RequestCorrelationFilter`)가 채울 때 |
| `event_name`·`endpoint`·`http_method` | keyword | `StructuredLogger` 이벤트 |
| `status_code` | integer | 〃 — `>= 400` 같은 범위 검색을 쓰므로 숫자여야 합니다 |
| `duration_ms` | long | 〃 — 백분위 집계 대상 |

### keyword와 text를 나눈 기준

- **keyword**: 정확히 일치, 집계, 정렬이 필요한 값(식별자·경로·열거형). 분석기를 거치지 않습니다.
- **text**: 사람이 읽는 문장에서 단어로 찾아야 하는 값(`message`, `stack_trace`). 토큰으로 쪼개져 집계에는 못 씁니다.

### 새 필드가 들어오면

`dynamic_templates`가 **모르는 문자열을 `keyword`로** 잡습니다(`ignore_above: 1024`). 기본 동작인 `text` + `.keyword` 이중 매핑을 막아 저장 중복과 매핑 폭증을 피하기 위해서입니다. 숫자는 그대로 `long`이 됩니다.

덕분에 `StructuredLogger`에 인자를 추가하거나 manyak-ai가 자기 필드를 실어 보내도(KNK-852) 템플릿을 고치지 않아도 됩니다. 다만 **집계·범위 검색을 쓸 만큼 중요한 필드는 위 `properties`에 명시**하는 편이 낫습니다. 타입을 의도대로 못 박고 문서로 남길 수 있기 때문입니다.

## 운영에서 달라지는 것

- `number_of_replicas: 0`은 단일 노드 기준입니다. 노드가 둘 이상이면 KNK-854에서 올립니다.
- 보관 정책(ISM)은 여기 없습니다. 도메인을 만드는 KNK-854에서 함께 적용합니다.

## 스토리 검색 인덱스

[KNK-1141](https://kimandkang.atlassian.net/browse/KNK-1141)의 검색은 로그 도메인에 `stories-dev`·`stories-prod`를 추가합니다. PostgreSQL이 정본이며 검색 문서는 재생성할 수 있는 파생 사본입니다. Flyway·dbdoc 변경은 없습니다.

### 환경별 최초 설정

관리자 셸에서 기존 `setup.sh`와 같은 `OS_URL` basic auth 패턴을 사용합니다. 자격증명이 들어간 URL은 문서·로그에 출력하지 않습니다. `setup-search.sh`는 `curl`·`jq`가 필요합니다.

1. 관리자 인증을 포함한 `OS_URL`, 환경 `ENV=dev` 또는 `prod`, 그 환경 ECS 태스크 역할 `TASK_ROLE_ARN`을 설정합니다.
2. `curl --fail --silent --show-error "${OS_URL%/}/_cat/plugins?v"`로 **analysis-nori** 설치를 확인합니다. 없으면 해당 Amazon OpenSearch 버전의 지원 패키지 설치 절차로 먼저 활성화합니다. 운영 매핑에는 standard 폴백이 없습니다.
3. `./opensearch/setup-search.sh`로 `manyak-search-${ENV}` 역할을 생성합니다. 인덱스 권한은 `stories-${ENV}*`의 `crud`·`create_index`, 클러스터 권한은 `cluster_composite_ops`입니다. 역할 매핑의 `backend_roles`를 `TASK_ROLE_ARN` 하나로 설정합니다.
4. 태스크 IAM 정책의 `es:ESHttp*`와 도메인 접근 정책을 확인합니다. dev는 FireLens 권한을 재사용합니다. prod는 [KNK-857](https://kimandkang.atlassian.net/browse/KNK-857) 연동 전이면 `manyak-terraform`에서 해당 권한을 먼저 반영해야 합니다.
5. 아래 설정으로 서버를 기동합니다. 태스크 정의의 환경변수 추가는 Terraform 반영이 필요하며 앱 이미지 배포만으로 추가되지 않습니다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `MANYAK_OPENSEARCH_ENDPOINT` | 빈 값 | `https://`·경로 없는 호스트. 빈 값이면 검색 503, 색인 no-op |
| `AWS_REGION` | `ap-northeast-2` | SigV4 서명 리전 |
| `MANYAK_OPENSEARCH_STORY_INDEX` | `stories-dev` | prod는 반드시 `stories-prod` |
| `MANYAK_OPENSEARCH_REINDEX_ON_STARTUP` | `false` | `true`로 기동하면 전체 스토리를 500개씩 bulk 재색인 |

서버 클라이언트는 `opensearch-java 3.10.0` + `AwsSdk2Transport`이며 자격증명은 AWS 기본 체인(운영 태스크 역할)으로 구합니다. JSON은 JSON-B(`JsonbJsonpMapper`, Yasson 3.0.5)로 처리하므로 Spring Jackson 3 매퍼에 영향을 주지 않습니다.

### 색인과 복구

매핑은 `src/main/resources/opensearch/stories-index.json`입니다. 제목·한 줄 소개·인물명은 nori mixed 분해와 품사 필터·lowercase를 쓰고 장르는 keyword입니다. `author`는 `enabled:false`로 원문만 저장하며, 내부 PK인 `author.id`는 기존 카드와 같이 null입니다. 검색 문서에 없는 `profileImageUrl`도 검색 카드에서는 null입니다.

스토리 제작·수정·공개 전환·삭제·표지 삭제·좋아요/취소·작성자 닉네임 변경이 커밋되면 비동기로 다시 색인합니다. 발행·공개·미삭제·회원 소유 조건을 모두 만족할 때만 `visible=true`입니다. 삭제와 비공개 전환도 문서를 없애지 않고 false로 다시 씁니다. 쓰기 실패는 warn 로그만 남깁니다. 저장 응답이 성공해도 검색 반영은 비동기 실행과 OpenSearch refresh 이후입니다.

초기 적재나 색인 실패 복구 시 `MANYAK_OPENSEARCH_REINDEX_ON_STARTUP=true`로 기동하고 `스토리 재색인 완료 (indexed=…, failed=…)` 로그를 확인합니다. **작업 후 false로 되돌립니다.** 매핑을 바꿀 때는 대상 환경의 검색 인덱스만 삭제한 뒤 재색인합니다. 로그 인덱스는 대상이 아닙니다. 재색인 중에는 일부 결과만 보일 수 있고, 페이지 사이 점수가 변하면 중복·누락이 가능합니다.

### 검증

`http/story/story-search.http`를 위에서부터 실행합니다. 검색은 `multi_match(title^3, oneLineIntro, genres, characterNames)` + `visible=true`, 정렬은 점수 내림차순·생성 밀리초 내림차순·UUID 오름차순입니다. 커서는 같은 trim 검색어에서만 사용할 수 있습니다. 빈 결과는 200이고, 검색어/커서 오류는 400, 미설정·검색 연결 장애는 503입니다.

`StorySearchOpenSearchTests`는 일회용 OpenSearch 2.19.4에서 비공개 제외·제목 관련도·search_after를 검증합니다. Docker가 없으면 스킵하고, 이미지에 nori가 없으면 **테스트 매핑만** standard로 바꾸며 결과 로그에 표시합니다. dev에서는 `_cat/plugins`와 `_analyze`로 `이야기꾼의` 같은 조사 포함 입력을 직접 확인하고, 태스크 역할로 색인 생성·읽기·쓰기·bulk 권한까지 검수해야 합니다.
