# OpenSearch 로그 인덱스

manyak 애플리케이션 로그를 OpenSearch에 적재하기 위한 자산입니다. 로컬 학습 스택(`docker-compose.observability.yml`)과 운영 도메인이 **같은 파일**을 씁니다.

| 파일 | 용도 |
|---|---|
| `setup.sh` | 스택 기동 후 한 번 실행하는 초기 설정 |
| `index-template.json` | 인덱스 템플릿(필드 타입 정의) |

## 시작하기

```bash
docker compose -f docker-compose.observability.yml up -d
./opensearch/setup.sh
```

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
