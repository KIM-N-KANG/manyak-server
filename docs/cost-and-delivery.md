# 비용과 배포 변천

이 문서는 운영 비용을 줄인 근거와 서버 배포 방식이 바뀐 과정을 적습니다. 비용은 최종 발표(2026-11-14) 전의 조정이고, 배포는 2026-11-20 v0.15.2까지입니다. 날짜는 KST입니다.

## 1. 비용

비공개 인프라 레포의 PR 세 개에서 사양을 바꿨습니다. 세 PR 모두 2026-11-10에 머지했고, 월 환산 비용이 29% 줄었습니다. ([2-14 비용 최적화](../README.md#2-14-비용-최적화))

| 순서 | 바꾼 것 |
| --- | --- |
| 1 | 태스크 사양을 줄이고 Fargate Spot을 적용 |
| 2 | 앱 태스크와 RDS, Redis를 Graviton으로 전환 |
| 3 | server와 ai 태스크 사양을 7일 사용량에 맞춰 축소 |

| 서비스 | 7일 사용량(CloudWatch 시간별 최대) | 사양 | 실행 방식 | 아키텍처 |
| --- | --- | --- | --- | --- |
| server | CPU p50 7.8%, p95 40.5%<br>메모리 약 47%(약 960MB) | 1 vCPU → 0.5 vCPU<br>메모리 2 GiB 유지 | 온디맨드 1개 + 나머지 Spot | x86_64 → ARM64 |
| ai | CPU p50 8.2%, p95 15.4%<br>메모리 약 18%(약 180MB) | 0.5 vCPU, 1 GiB → 0.25 vCPU, 512 MiB | 온디맨드 1개 + 나머지 Spot | x86_64 → ARM64 |
| notification | 메모리 최대 약 400MB(힙이 아직 다 자라지 않은 값) | 0.25 vCPU, 1 GB 유지 | 모두 Spot | x86_64 → ARM64 |
| 진단 에이전트 | 근거 수치 없음 | 1 vCPU, 2 GB → 0.5 vCPU, 1 GB | 모두 Spot | x86_64 유지(단일 아키텍처 이미지) |
| RDS PostgreSQL | | db.t3.micro → db.t4g.micro(Multi-AZ 유지) | | Graviton |
| ElastiCache Redis | | cache.t3.micro 2대 → cache.t4g.micro 2대 | | Graviton |

- server CPU의 최대값 102.5%는 배포 때 기동으로 생긴 값으로 봤습니다. 같은 사용량이면 0.5 vCPU에서 p95는 약 81%가 됩니다. 오토스케일링 목표는 CPU 70%이고 늘어난 태스크(최대 4개)는 Spot으로 뜹니다.
- notification은 JVM 최대 힙이 512MB라 메모리를 512MB로 줄이면 메모리 부족으로 종료될 수 있어 1 GB로 두었습니다.
- ai는 uvicorn 워커가 하나라 CPU를 줄여도 처리 프로세스 수는 같습니다.
- ARM64로 옮기기 전에 server, ai, notification 이미지가 모두 amd64와 arm64를 함께 담고 있는지 확인했습니다. 세 레포 모두 두 아키텍처로 빌드합니다.
- server 기동 시간은 x86 1 vCPU에서 76~89초, Graviton 1 vCPU에서 37~54초였습니다. 0.5 vCPU에서는 약 110초로 추정했고, 헬스 검사 한도(210초, 유예 300초) 안입니다.
- OTel Collector가 도는 수집 태스크는 컨테이너 한도와 Data Prepper 힙을 함께 조정해야 해서 사양을 바꾸지 않았습니다. 아키텍처만 ARM64로 바꿨습니다.

## 2. 배포 변천

| 단계 | 기간 | 배포 방식 | 첫 릴리스 |
| --- | --- | --- | --- |
| EC2와 Docker Compose | 6/25~8/24 | SSM으로 EC2의 배포 스크립트 실행<br>`docker compose pull`, `up -d server` | v0.1.0([#58](https://github.com/KIM-N-KANG/manyak-server/pull/58)) |
| ECS 롤링 배포 | 8/24~11/19 | `:latest` 승격 후 ECS 서비스 재배포 | v0.2.13([#203](https://github.com/KIM-N-KANG/manyak-server/pull/203)) |
| 앱 호스트 배포(발표 이후) | 11/19~ | SSM 문서로 호스트의 배포 스크립트 실행<br>kamal-proxy로 무중단 전환 | v0.15.1([#563](https://github.com/KIM-N-KANG/manyak-server/pull/563)) |

### 2-1. 단계마다 그대로 둔 것

| 항목 | 도입 | 내용 |
| --- | --- | --- |
| 두 아키텍처 이미지 | 6/12([#7](https://github.com/KIM-N-KANG/manyak-server/pull/7)) | buildx로 amd64, arm64를 함께 빌드 |
| GitHub OIDC | 6/24([#46](https://github.com/KIM-N-KANG/manyak-server/pull/46)) | 저장된 키 없이 AWS 역할을 받아 ECR에 푸시 |
| 최신 커밋 게이트 | 6/25([#52](https://github.com/KIM-N-KANG/manyak-server/pull/52)) | 늦게 끝난 옛 빌드가 최신 배포를 덮지 않게 함 |
| 불변 이미지 태그 | 6/25([#52](https://github.com/KIM-N-KANG/manyak-server/pull/52)) | 커밋 SHA 7자리 태그로 배포 |
| 릴리스 머지의 테스트 재사용 | 10/7([#311](https://github.com/KIM-N-KANG/manyak-server/pull/311)) | 같은 트리의 dev 테스트 결과를 다시 씀 |

### 2-2. EC2와 Docker Compose

배포 잡은 SSH 대신 SSM Run Command로 EC2의 `deploy.sh`를 실행했습니다. 스크립트는 ECR에 다시 로그인하고 Secrets Manager에서 `.env`를 다시 만든 뒤 server 컨테이너만 새 이미지로 올렸습니다. 잡은 명령 상태를 최대 10분 확인하고 `/actuator/health`로 스모크 검사를 했습니다.

### 2-3. ECS 롤링 배포

트래픽을 ECS로 옮긴 뒤 운영 배포 잡도 ECS로 바꿨습니다([#201](https://github.com/KIM-N-KANG/manyak-server/pull/201)). 태스크 정의는 `:latest`를 가리키고, 잡은 새 태스크 정의를 등록하지 않습니다.

1. 빌드 잡이 ECR에 `:short-sha`만 푸시합니다.
2. main의 최신 커밋 이미지가 아직 없으면 배포를 건너뜁니다.
3. 서비스가 안정될 때까지 기다린 뒤, 직전 digest를 기억하고 최신 커밋을 `:latest`로 승격합니다.
4. `force-new-deployment`로 재배포하고 그 배포의 `rolloutState`를 확인합니다. 회로 차단기가 배포를 되돌린 경우도 실패로 잡기 위해서입니다.
5. 새 태스크의 server, ai 컨테이너 헬스와 `/actuator/health` 스모크를 확인합니다.
6. 실패하거나 취소되면 `:latest`를 직전 digest로 되돌리고 다시 배포합니다.

EC2 수동 되돌리기 잡은 8/25에 지웠습니다([#206](https://github.com/KIM-N-KANG/manyak-server/pull/206)).

### 2-4. 발표 이후

운영과 dev 모두 EC2 앱 호스트 한 대로 옮겼습니다(dev [#559](https://github.com/KIM-N-KANG/manyak-server/pull/559) 11/18, 운영 [#562](https://github.com/KIM-N-KANG/manyak-server/pull/562) 11/19). 배포 잡은 SSM 문서로 호스트의 `deploy.sh`를 실행합니다. 스크립트는 새 컨테이너의 헬스를 확인하고 kamal-proxy를 새 컨테이너로 돌립니다. 이어서 옛 컨테이너의 SSE 연결이 끝나기를 최대 240초 기다린 뒤 옛 컨테이너를 멈춥니다. 세 레포의 배포는 호스트에서 잠금으로 한 번에 하나씩 실행합니다. 22번 포트와 SSH 키는 쓰지 않습니다. 운영 스모크가 실패하면 직전 이미지로 되돌리고, `:latest`는 성공한 뒤에만 옮깁니다. dev 전환 PR에서 83초 배포 동안 1초 간격 헬스 237번과 SSE 채팅 20건이 모두 성공했습니다.

같은 시기에 gitleaks와 CodeQL 검사를 더했습니다([#564](https://github.com/KIM-N-KANG/manyak-server/pull/564), v0.15.2).
