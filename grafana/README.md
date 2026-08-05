# Grafana 대시보드

운영 메트릭 대시보드를 JSON 모델로 관리합니다. Grafana UI에서 직접 만들지 않고 이 파일을 import합니다.

| 파일 | 내용 |
| --- | --- |
| `manyak-server-overview.json` | RED · AI 호출 지연 · 스토리 완성 · 인프라 · 무료 티어 예산 (KNK-782) |

## 왜 이 레포인가

대시보드 쿼리는 **서버 코드가 정의한 메트릭 이름과 라벨에 의존**합니다. KNK-784로 `outcome`이 2값에서 3값이 되면서 알림 쿼리가 바뀐 것이 그 예입니다. 같은 레포에 두면 계약이 바뀌는 PR에서 대시보드도 함께 고쳐야 한다는 게 리뷰에 드러납니다 — `http/` 디렉터리와 같은 성격입니다.

Terraform provider(`grafana_dashboard`)로 관리하면 IaC가 되어 `manyak-terraform`으로 가야 하지만, 손으로 import하는 JSON은 IaC가 아닙니다. 대시보드가 늘어 수동 import가 번거로워지면 그때 옮기면 되고, JSON은 그대로 재사용됩니다.

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

## 히스토그램 상한

`application.yml`의 `maximum-expected-value`가 패널 해석에 직접 영향을 줍니다.

| 메트릭 | 구간 | 함의 |
| --- | --- | --- |
| `http.server.requests` | 10ms~10s | **AI 대기 엔드포인트의 HTTP p95는 10초에서 뭉개집니다.** 의도된 선택 — 그 경로 지연은 전용 타이머로 봅니다 |
| `manyak.ai.call.duration` | 100ms~240s | AI read 타임아웃 최대 180초(compile)보다 위 |
| `manyak.story.creation.duration` | 100ms~240s | 위와 같음 |

## 무료 티어 예산

한도는 **10,000 active series**입니다. 추정 사용량은 약 5,500이며 그중 **88%가 `http.server.requests` 히스토그램**입니다(엔드포인트 40 × 상태 ~2.5 × 버킷 49).

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
