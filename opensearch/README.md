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
