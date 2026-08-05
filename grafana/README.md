# Grafana 대시보드

운영 메트릭 대시보드를 JSON 모델로 관리합니다. Grafana UI에서 직접 만들지 않고 이 파일을 import합니다.

| 파일 | 내용 |
| --- | --- |
| `manyak-server-overview.json` | RED · AI 호출 지연 · 스토리 완성 · 인프라 · 무료 티어 예산 (KNK-782) |

## 왜 이 레포인가

대시보드 쿼리는 **서버 코드가 정의한 메트릭 이름과 라벨에 의존**합니다. KNK-784로 `outcome`이 2값에서 3값이 되면서 알림 쿼리가 바뀐 것이 그 예입니다. 같은 레포에 두면 계약이 바뀌는 PR에서 대시보드도 함께 고쳐야 한다는 게 리뷰에 드러납니다 — `http/` 디렉터리와 같은 성격입니다.

Terraform provider(`grafana_dashboard`)로 관리하면 IaC가 되어 `manyak-terraform`으로 가야 하지만, 손으로 import하는 JSON은 IaC가 아닙니다. 대시보드가 늘어 수동 import가 번거로워지면 그때 옮기면 되고, JSON은 그대로 재사용됩니다.

## 무엇을 재는가

| 메트릭 | 태그 | 계측 지점 | 재는 구간 |
| --- | --- | --- | --- |
| `http.server.requests` | `uri`(경로 템플릿)·`method`·`status`·`outcome` | Spring Boot 자동 | 컨트롤러 진입 ~ 응답 |
| `manyak.ai.call.duration` | `feature`(4종)·`outcome`(2값) | `AiCallRecorder.record` | AI 클라이언트 호출 직전 ~ 응답/예외 |
| `manyak.story.creation.duration` | `outcome`(3값) | `SimpleStoryCreationService.recordCreationDuration` | 완성 콜백 전체(AI + 저장 + 크레딧) |
| JVM · 프로세스 · HikariCP | 바인더별 | Micrometer 기본 바인더 | 자원 상태 |

`feature`는 `storyline_generation`·`story_completion`·`chat_response`·`choice_generation`입니다. `choice_generation`은 현재 독립 AI 호출이 없어(선택지가 채팅 턴 응답에 함께 옴) 라인이 안 나오는 게 정상입니다.

**측정에서 일부러 뺀 것** — `manyak.story.creation.duration`은 AI 호출 없이 저장된 결과를 돌려주는 두 경로를 제외합니다. 멱등 재요청(COMPLETED replay)과 회수 재실행 재구성입니다. 포함하면 밀리초짜리 조회가 섞여 p95가 실제 생성 비용보다 낙관적으로 왜곡됩니다.

## 어떻게 흘러가는가

```
Micrometer Timer  →  OtlpMeterRegistry  ──60초──▶  Grafana Cloud OTLP 게이트웨이
   (앱 코드)          (spring-boot)                   (외부 저장·조회)
```

**push 방식입니다(스크레이프 아님).** 그래서 운영은 `/actuator/prometheus`를 노출하지 않고 인바운드 경로도 열지 않습니다. Prometheus·Grafana를 별도 EC2에 자체 호스팅하지 않는 이유이기도 합니다 — 단일 인스턴스 운영에서 관측 대상과 관측자가 같이 죽고, 관리 비용이 이득보다 큽니다.

**켜지는 조건은 세 가지가 모두 참일 때입니다.**

1. 시크릿에 `SERVER_MANAGEMENT_OTLP_METRICS_EXPORT_URL`·`..._HEADERS_AUTHORIZATION`이 **둘 다** 있음
2. `deploy.sh`가 그 둘을 `.env`에 기록(한쪽만 있으면 3줄 전부 생략)
3. `MANYAK_OTLP_METRICS_ENABLED=true`

기본값은 **off**입니다. micrometer의 기본 endpoint가 `http://localhost:4318`이라, 켠 채 endpoint를 주지 않으면 모든 환경이 매 step마다 헛푸시합니다.

**설정 위치는 `application.yml`의 `management.*`** 입니다. 히스토그램 on/off와 버킷 구간이 여기 있고, 이 값이 대시보드 해석에 직접 영향을 줍니다(아래 히스토그램 상한 절).

**이름이 두 번 바뀝니다.** 코드의 `manyak.ai.call.duration`이 Prometheus 노출에서 `manyak_ai_call_duration_seconds_*`가 되고, OTLP 전송에서는 기본 시간 단위가 달라 **`_milliseconds_*`** 로 도착합니다. 쿼리를 쓸 때 이 차이가 가장 흔한 실수입니다.

## 왜 이 지표들인가

### 3층으로 나눠 본다 — 원인을 좁히기 위해

한 요청은 여러 구간을 지납니다. 지표를 한 층만 보면 "느리다"까지만 알고 **어디가** 느린지는 모릅니다. 그래서 세 층을 같은 시간축에 놓습니다.

```
HTTP 입구        http.server.requests           서비스가 밖에서 정상으로 보이나
  └ 유스케이스   manyak.story.creation.duration  스토리 완성이라는 기능 자체가 느린가
      └ 외부 경계 manyak.ai.call.duration        AI가 느린가
```

**HTTP만으로는 부족한 이유** — 간편 스토리 완성 요청 하나에는 소유권 확인, 크레딧 또는 게스트 한도 처리, AI compile 호출, 여러 테이블 저장이 함께 들어갑니다. HTTP p95가 올랐다는 사실만으로는 AI가 느린 건지 저장이 느린 건지 구분할 수 없습니다.

**AI를 따로 재는 이유** — AI read 타임아웃이 스토리라인 90초·compile 180초입니다. 이 서비스에서 지연의 지배적 요인이고, 우리가 통제하지 못하는 외부 의존입니다. 멘토 피드백이 AI 응답시간을 짚은 것도 이 때문입니다. `AiCallRecorder`라는 **공통 호출 경계 한 곳**에서 재기 때문에 기능이 늘어도 계측이 빠지지 않습니다.

**세 층을 함께 읽는 법**

| 관측 | 해석 |
| --- | --- |
| AI p95 ↑ · HTTP p95 ↑ | AI 지연이 그대로 사용자에게 전달되는 중 |
| AI p95 정상 · 완성 p95 ↑ | AI 밖(저장·크레딧·락 대기)에서 시간을 쓰는 중 |
| 완성 p95 정상 · HTTP p95 ↑ | 다른 엔드포인트 문제. `uri`별로 더 파고들 근거 |

### 이 대시보드가 답할 수 없는 것

**두 p95를 빼서 AI 기여도를 구할 수 없습니다.** 서로 다른 표본에서 계산된 분위수라 산술이 성립하지 않습니다. 요청별 구간 시간을 연결하려면 트레이스나 `request_id` 기반 별도 계측이 필요합니다. 이 대시보드는 **원인 후보를 좁히는 도구**이지 원인을 확정하는 도구가 아닙니다.

개별 사건 추적도 여기서 못 합니다 — 그건 구조화 로그와 `ai_call_logs`의 몫입니다. 메트릭에 `user_id`·`story_id` 같은 고유값을 넣지 않는 이유이기도 합니다(시계열이 무한히 늘어남).

### 인프라를 같은 화면에 두는 이유

운영은 **`t3.small` 한 대에 server와 ai 컨테이너가 함께** 떠 있습니다. 즉 자원 경합이 실재하는 가설입니다.

지연이 늘었을 때 "외부 AI가 느린 것"과 "우리 앱이 자원이 없어 느린 것"은 대응이 완전히 다릅니다. CPU·힙·GC·커넥션 풀을 같은 시간축에 놓으면 그 구분이 눈으로 됩니다.

| 지표 | 무엇을 의심하게 하나 |
| --- | --- |
| CPU | 컨테이너 간 경합, 스파이크성 부하 |
| 힙 · GC pause | 메모리 압박 → GC 정지 → 응답시간 전반 상승 |
| HikariCP `pending` | 커넥션 대기 = DB 병목. **`pending`이 0이 아니면 그것만으로 신호** |

### active series를 대시보드에 둔 이유

이건 서비스 지표가 아니라 **비용 안전장치**입니다.

무료 티어 한도는 10,000이고, **트라이얼 동안은 한도가 안 걸려 넘겨도 아무 증상이 없다가 만료 시점에 데이터가 잘리기 시작합니다.** 그때 원인을 찾기 어렵습니다. 관측이 조용히 죽는 것을 관측으로 막습니다.

시계열은 트래픽이 아니라 **라벨 조합 수**로 늘어나므로, 위험 신호는 트래픽 급증이 아니라 **엔드포인트 추가**입니다(아래 예산 절 참고).

### 일부러 넣지 않은 것

- **알림** — 기준선이 없어 임계값이 근거 없는 숫자가 됩니다(아래 참고)
- **`jdbc_connections_*`** — `hikaricp_*`와 같은 값이라 중복
- **요청별 트레이스** — 이 대시보드의 범위가 아닙니다. 필요해지면 별도 계측

## Import

```
Grafana → Dashboards → New → Import → JSON 붙여넣기 → Load
```

import 시 변수 두 개를 고릅니다.

| 변수 | 고를 것 |
| --- | --- |
| `datasource` | 스택의 Prometheus 데이터소스 (`grafanacloud-<stack>-prom`) |
| `usage_datasource` | Grafana Cloud **usage/billing** 데이터소스 — active series 패널 전용 |

`service` 변수는 데이터에서 자동으로 채워집니다(운영 `manyak-server` / 로컬 `manyak-server-local`).

수정 후에는 `Dashboard settings → JSON Model`에서 복사해 이 파일에 반영하고 커밋합니다. `version` 필드는 그대로 둬도 무방합니다.

## 쿼리 규칙 — 실측으로 확인된 함정

**라벨은 `job`이 아니라 `service_name`입니다.** 초기 설계 문서에 `job`으로 적혀 있었으나 로컬 실습과 운영 수신 모두 `service_name`으로 확인됐습니다. `job`으로 쓰면 조용히 빈 패널이 됩니다.

**이름이 로컬과 다릅니다.** 로컬 `/actuator/prometheus`는 `..._seconds_*`인데 Grafana Cloud에는 **`..._milliseconds_*`** 로 도착합니다(OTLP 기본 시간 단위 차이). 새 쿼리를 쓸 때는 Metrics browser에서 실제 수신 이름을 먼저 확인하세요.

**`outcome`은 3값입니다**(KNK-784).

| 값 | 뜻 | 소요 |
| --- | --- | --- |
| `success` | 생성·저장까지 완료 | 수 초~180초 |
| `failure` | 생성을 시도하다 깨짐(AI 실패·타임아웃·저장 경합) | 수 초~180초 |
| `rejected` | 생성 시도 **이전** 4xx 거부(세션 없음·소유권·크레딧 등) | 밀리초 |

**알림과 실패 p95는 `outcome="failure"`만 봅니다.** `rejected`를 섞으면 밀리초짜리 거부가 분포를 끌어내려, AI가 실제로 느려지는데 지표는 개선된 것처럼 보이는 역전이 생깁니다.

`manyak.ai.call.duration`의 `outcome`은 2값 그대로입니다 — `AiCallRecorder`가 AI 호출만 감싸므로 거부가 섞일 여지가 없습니다.

**DB 커넥션 풀은 `hikaricp_*`와 `jdbc_*`가 같은 값을 두 이름으로 내보냅니다.** 둘 중 하나만 쓰세요(이 대시보드는 `hikaricp_*`).

**`jvm_gc_pause_*`는 GC가 실제로 발생한 뒤에야 나타납니다.** 기동 직후 빈 패널은 정상입니다. percentile histogram을 켜지 않아 `_bucket`이 없으므로 p95 대신 평균(`rate(_sum)/rate(_count)`)으로 봅니다.

**비율 패널은 분자가 빌 때를 처리해야 합니다.** 5xx가 한 건도 없으면 `sum(rate(...{status=~"5.."}))`는 0이 아니라 **빈 벡터**이고, 빈 벡터를 나누면 결과도 비어 `No data`가 됩니다. "오류 없음"과 "수집 실패"가 화면에서 구분되지 않으므로 채워 줍니다.

```promql
# 라벨 없는 집계 — vector(0)으로 대체
100 * (sum(rate(m{status=~"5.."}[5m])) or vector(0)) / clamp_min(sum(rate(m[5m])), 0.0001)

# by (feature) 집계 — 0 * rate(전체)를 or로 더해 feature별 0을 만든다
100 * sum by (feature) (rate(m{outcome="failure"}[5m]) or 0 * rate(m[5m]))
      / clamp_min(sum by (feature) (rate(m[5m])), 0.0001)
```

분모의 `clamp_min`은 트래픽이 0인 새벽에 0으로 나누는 것을 막습니다.

## 히스토그램 상한

`application.yml`의 `maximum-expected-value`가 패널 해석에 직접 영향을 줍니다.

| 메트릭 | 구간 | 함의 |
| --- | --- | --- |
| `http.server.requests` | 10ms~10s | **AI 대기 엔드포인트의 HTTP p95는 10초에서 뭉개집니다.** 의도된 선택 — 그 경로 지연은 전용 타이머로 봅니다 |
| `manyak.ai.call.duration` | 100ms~240s | AI read 타임아웃 최대 180초(compile)보다 위 |
| `manyak.story.creation.duration` | 100ms~240s | 위와 같음 |

## 무료 티어 예산

한도는 **10,000 active series**입니다.

| | 값 |
| --- | --- |
| 사전 추정 | 약 5,500 |
| **실측(2026-08-06 배포 1시간 후)** | **약 1,200 — 아직 상승 중** |

**추정이 4.5배 과대했습니다.** 엔드포인트 40개 × 상태 2.5종이 전부 관측된다고 가정했는데, 실제로는 **호출된 조합만 시계열이 생깁니다.** 배포 직후엔 헬스체크와 일부 엔드포인트만 활성이라 훨씬 적습니다.

다만 **1,200은 정상 상태가 아닙니다.** 각 엔드포인트가 처음 호출될 때마다 늘어나므로 며칠 지나 평탄해진 값을 봐야 합니다. 그때 이 표를 갱신하세요.

시계열은 트래픽 양이 아니라 **라벨 조합 수**로 늘어납니다. 앰플리튜드 기준 피크(9,937 이벤트/일)와 평상시(약 750)가 13배 차이지만 시계열 수는 거의 같습니다. 실제로 예산을 위협하는 것은 **엔드포인트 추가**(하나당 50~150 시계열)입니다.

한도에 근접하면 순서대로 씁니다.

1. `http.server.requests` 상한을 10s → 3s (약 700 절감)
2. HTTP 히스토그램을 끄고 클라이언트 계산 p95로 (약 4,600 절감 — 단일 인스턴스라 재집계 손실이 실질적으로 없음)

## 아직 없는 것

**알림은 설정하지 않았습니다.** 수신 시작이 2026-08-06이라 기준선이 없고, 근거 없는 임계값은 오발화만 만듭니다. 며칠 데이터를 모은 뒤 임계값과 **No Data 동작**을 함께 설계합니다.

No Data가 중요한 이유: 서버가 내려가면 규칙이 No Data로 발화합니다. 로컬 실습 때 이것 때문에 규칙을 Pause했습니다. 운영은 서비스 가용성 알림과 메트릭 수집 장애 알림을 분리해야 합니다.

## 관련

- 스펙: `knk-harness` `docs/product-specs/4-backend.md` §4-7 메트릭
- 수동 검증: `http/common/metrics-prometheus.http`
- 계측 코드: `AiCallRecorder`, `SimpleStoryCreationService.recordCreationDuration`, `application.yml`의 `management.metrics.distribution`
