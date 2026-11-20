<div align="center">

<img src="docs/images/manyak-logo.svg" alt="마냑 로고" width="180">

# manyak-server

AI 스토리 채팅 서비스 **마냑**의 API 서버

[![Docker Image](https://github.com/KIM-N-KANG/manyak-server/actions/workflows/docker-image.yml/badge.svg?branch=dev)](https://github.com/KIM-N-KANG/manyak-server/actions/workflows/docker-image.yml)

</div>

## 목차

- [1. 프로젝트 개요](#1-프로젝트-개요)
  - [1-1. 프로젝트 소개](#1-1-프로젝트-소개)
  - [1-2. 시스템 구성도](#1-2-시스템-구성도)
  - [1-3. 주요 기능](#1-3-주요-기능)
  - [1-4. 개발 환경](#1-4-개발-환경)
- [2. 개발 결과물](#2-개발-결과물)
  - [2-1. 백엔드 아키텍처](#2-1-백엔드-아키텍처)
  - [2-2. 인프라 아키텍처](#2-2-인프라-아키텍처)
  - [2-3. 데이터베이스 ERD와 마이그레이션](#2-3-데이터베이스-erd와-마이그레이션)
  - [2-4. API 문서화와 수동 검증](#2-4-api-문서화와-수동-검증)
  - [2-5. 테스트](#2-5-테스트)
  - [2-6. 리팩터링](#2-6-리팩터링)
  - [2-7. CI/CD 파이프라인](#2-7-cicd-파이프라인)
  - [2-8. 채팅 턴 스트리밍](#2-8-채팅-턴-스트리밍)
  - [2-9. 알림 파이프라인](#2-9-알림-파이프라인)
  - [2-10. 검색](#2-10-검색)
  - [2-11. 관측과 장애 진단](#2-11-관측과-장애-진단)
  - [2-12. 부하 테스트](#2-12-부하-테스트)
  - [2-13. 비용 최적화](#2-13-비용-최적화)
- [3. 수행 방법](#3-수행-방법)
  - [3-1. 스펙 주도 개발](#3-1-스펙-주도-개발)
  - [3-2. 형상 관리와 릴리스](#3-2-형상-관리와-릴리스)
  - [3-3. AI 에이전트 협업](#3-3-ai-에이전트-협업)
- [4. 개발 가이드](#4-개발-가이드)

## 1. 프로젝트 개요

### 1-1. 프로젝트 소개

마냑은 사용자가 고른 키워드로 AI가 스토리를 만들고, 그 스토리 속 인물들과 채팅으로 이야기를 이어 가는 서비스입니다. 서비스 주소는 <https://www.manyak.app>입니다.

이 레포는 마냑의 API 서버입니다. 회원과 인증, 스토리 제작과 탐색, 채팅 턴 생성, 재화(이프) 결제, 알림, 관리자 기능을 맡습니다. AI•SW 마에스트로 17기 김앤강 팀이 2026년 6월부터 개발했습니다.

관련 공개 레포는 다음과 같습니다.

| 레포 | 역할 |
| --- | --- |
| [knk-harness](https://github.com/KIM-N-KANG/knk-harness) | 도메인 스펙과 팀 공용 LLM 작업 하네스 |
| [manyak-ai](https://github.com/KIM-N-KANG/manyak-ai) | 스토리 생성, 채팅 턴 생성, 검수를 맡는 AI 서버(Python, FastAPI) |
| [manyak-notification](https://github.com/KIM-N-KANG/manyak-notification) | 큐를 소비해 푸시를 보내는 알림 서비스(Kotlin, Spring Boot) |
| [manyak-web](https://github.com/KIM-N-KANG/manyak-web) | 웹 클라이언트와 BFF(Next.js) |
| [manyak-android](https://github.com/KIM-N-KANG/manyak-android) | Android 앱 |
| [manyak-admin](https://github.com/KIM-N-KANG/manyak-admin) | 관리자 페이지 |

### 1-2. 시스템 구성도

```mermaid
flowchart LR
    subgraph Client["클라이언트"]
        WEB["웹<br/>Next.js BFF"]
        AND["Android 앱"]
        ADM["관리자 페이지"]
    end
    subgraph Apps["ECS Fargate"]
        SRV["manyak-server<br/>Spring Boot"]
        AI["manyak-ai<br/>FastAPI"]
        NOTI["manyak-notification<br/>Spring Boot"]
    end
    subgraph Data["데이터"]
        PG[("RDS PostgreSQL")]
        RD[("ElastiCache Redis")]
        OS[("OpenSearch")]
        S3[("S3와 CloudFront")]
    end
    LLM["LLM 제공자<br/>OpenAI, Anthropic, Gemini, DeepSeek"]
    FCM["FCM"]
    OBS["관측<br/>OTel Collector"]

    WEB --> ALB["ALB"]
    AND --> ALB
    ADM --> ALB
    ALB --> SRV
    SRV -->|"동기 REST, SSE"| AI
    AI --> LLM
    SRV --> PG
    SRV --> RD
    SRV --> OS
    SRV --> S3
    SRV -->|"아웃박스"| SQS["SQS"]
    SQS --> NOTI
    NOTI -->|"내부 API"| SRV
    NOTI --> FCM
    Apps -.->|"OTLP"| OBS

    classDef comp fill:#E8F2E8,stroke:#527A59,color:#182D1D
    classDef task fill:#F5D9C9,stroke:#B97450,color:#452B1D
    class SRV comp
    class AI,NOTI task
```

웹은 브라우저의 모든 API 호출을 Next.js BFF로 받아 서버에 넘깁니다. 서버는 요청마다 요청 ID를 붙이고, AI 서버와 알림 서비스까지 같은 ID와 트레이스를 이어 보냅니다. LLM 호출은 별도 AI 서버에, 푸시 발송은 큐 뒤의 알림 서비스에 맡겼습니다.

### 1-3. 주요 기능

<table>
  <tr>
    <td align="center"><img src="docs/images/screen-quick-create-genre.png" width="180" alt="간편 제작 장르 선택"></td>
    <td align="center"><img src="docs/images/screen-quick-create-protagonist.png" width="180" alt="간편 제작 주인공 설정"></td>
    <td align="center"><img src="docs/images/screen-quick-create-style.png" width="180" alt="간편 제작 이미지 스타일 선택"></td>
    <td align="center"><img src="docs/images/screen-chat-choices.png" width="160" alt="채팅 선택지"></td>
    <td align="center"><img src="docs/images/screen-realtime-image.png" width="180" alt="채팅 중 실시간 이미지"></td>
  </tr>
  <tr>
    <td align="center">간편 제작: 장르</td>
    <td align="center">간편 제작: 주인공</td>
    <td align="center">간편 제작: 이미지 스타일</td>
    <td align="center">채팅 선택지</td>
    <td align="center">실시간 이미지</td>
  </tr>
</table>

- **스토리 제작**: 장르, 주인공, 주변 인물을 키워드로 고르면 AI가 스토리라인 세 개를 제안하고, 고른 스토리라인으로 스토리를 완성합니다. 직접 작성하는 일반 제작도 있습니다.
- **채팅**: 스토리 속 인물과 턴 단위로 대화합니다. 응답은 SSE로 흘려보내고, 재생성, 선택지, 실시간 이미지, 입력 수정, 포크, 공유를 지원합니다.
- **탐색과 검색**: 홈 섹션, 공개 목록, 제목과 인물 이름 검색을 제공합니다.
- **이프와 결제**: 턴과 이미지 생성에 재화 이프를 쓰고, 체험 이프와 결제 충전을 원장으로 관리합니다.
- **알림**: 스토리 완성, 검수 결과, 출석, 캠페인 푸시와 알림함을 제공합니다.
- **관리자**: 운영 지표, 검수와 공개 중단, 캠페인, 이프 조정 기능을 관리자 API로 제공합니다.

### 1-4. 개발 환경

| 항목 | 버전 |
| --- | --- |
| Kotlin | 2.3.21 |
| Spring Boot | 4.1.2(Web MVC, Data JPA, Security, Actuator) |
| Java | 25 |
| Gradle | 9.5.1 |
| PostgreSQL | 16(로컬 기준), Flyway |
| Redis | 7 |
| OpenSearch | 클라이언트 3.10.0, nori 분석기 |
| 테스트 | JUnit 5, AssertJ, Testcontainers 2.0.3, ArchUnit 1.5.1 |
| 문서 | springdoc-openapi 3.0.3, tbls |
| 관측 | Spring Boot OpenTelemetry, Sentry |

## 2. 개발 결과물

### 2-1. 백엔드 아키텍처

서버는 모듈러 모놀리스입니다. `com.knk.manyak` 아래 최상위 패키지 하나가 모듈 하나이고, 모듈은 14개입니다. 아래 그림은 실제 코드의 모듈 간 의존을 줄인 것입니다. 모든 화살표는 위에서 아래로만 향하고 순환이 없습니다. 모든 모듈이 쓰는 `global`은 그림에서 뺐습니다.

```mermaid
flowchart TB
    admin["admin<br/>관리자 API"] --> push
    migration["migration<br/>게스트 이관"] --> chat
    chat["chat<br/>채팅과 턴"] --> story
    push["push<br/>알림"] --> story
    push --> notice
    search["search<br/>검색"] --> story
    story["story<br/>스토리"] --> credit
    story --> image["image<br/>이미지"]
    invite["invite<br/>초대"] --> credit
    credit["credit<br/>이프 원장"] --> auth
    notice["notice<br/>공지"] --> auth
    auth["auth<br/>회원과 인증"] --> adminkit["adminkit<br/>감사 로그, 관리자 쿼리 도구"]
    feedback["feedback<br/>피드백"]

    classDef comp fill:#E8F2E8,stroke:#527A59,color:#182D1D
    class admin,migration,chat,push,search,story,invite,credit,notice,auth,adminkit,image,feedback comp
```

모듈 경계는 ArchUnit 테스트(`ModuleBoundaryTests`)가 지킵니다. 위반이 하나라도 생기면 빌드가 실패하고 위반한 클래스 쌍을 출력합니다.

| 규칙 | 내용 |
| --- | --- |
| R1 | 다른 모듈의 리포지토리와 엔티티를 직접 참조하지 않는다. 그 모듈의 조회 서비스, 잠금 진입점, 명령을 호출한다 |
| R2 | 모듈 간 순환을 만들지 않는다. 아래 모듈이 위 모듈을 알아야 하면 인터페이스나 이벤트를 아래 모듈에 둔다 |
| G | `global`은 도메인 모듈에 의존하지 않는다 |

외부에 노출하는 식별자는 모두 UUID(`public_id`)이고 순차 PK는 API에 내보내지 않습니다. 오류 응답은 `code`, `message`, `details` 형태로 통일했습니다.

### 2-2. 인프라 아키텍처

> 아래 구성은 SW마에스트로 과정 종료 시점(2026년 11월 중순) 기준입니다. 그 뒤에는 비용을 줄이려고 단일 호스트 구성으로 줄여 운영합니다. 이 레포의 배포 워크플로가 호스트 배포인 것은 그래서입니다.

![클라우드 아키텍처 Ver.6](docs/images/cloud-architecture-v6.png)

Ver.6은 두 가용 영역에 걸친 이중화 구성입니다.

- **앱**: server, ai, notification을 각각 ECS Fargate 서비스로 두고 서비스마다 오토스케일링합니다. notification은 Cloud Map 주소로 서버의 내부 API를 부르고, 공개 ALB는 내부 경로를 막습니다.
- **데이터**: RDS PostgreSQL은 Multi-AZ, ElastiCache Redis는 Primary와 Replica입니다. 가용 영역마다 NAT Gateway를 둡니다.
- **관측**: 세 앱의 OTLP를 OTel Collector가 받아 로그와 트레이스는 OpenSearch로, 메트릭은 Amazon Managed Service for Prometheus로 보냅니다.
- **배포**: 레포별 GitHub OIDC 역할로 ECR에 이미지를 올리고 ECS 서비스를 교체합니다.

<details>
<summary>Ver.1 ~ Ver.5 변화 보기</summary>

| 버전 | 중점 | 내용 |
| --- | --- | --- |
| Ver.1 | 첫 운영과 계층 분리 | EC2 한 대의 Docker Compose에서 server와 ai를 실행. 퍼블릭, 앱, 데이터 서브넷을 나누고 RDS와 Redis는 관리형 |
| Ver.2 | 컨테이너 운영 전환 | ECS 1태스크로 옮기고 FireLens 로그를 OpenSearch로. EC2 회수 |
| Ver.3 | 서비스 분리 | 알림 서비스와 SQS, DLQ, Cloud Map 추가. 내부 API 공개 차단. 검색용 OpenSearch 색인(nori) |
| Ver.4 | 이중화 계획 | 두 가용 영역 배치, RDS Multi-AZ, Redis 복제, 가용 영역별 NAT를 계획 |
| Ver.5 | 분산 추적과 이중화 적용 | Data Prepper로 트레이스를 모아 OpenSearch 서비스 맵을 만듦. 서비스마다 최소 2개, 최대 4개 태스크 |

**Ver.1**

![클라우드 아키텍처 Ver.1](docs/images/cloud-architecture-v1.png)

**Ver.2**

![클라우드 아키텍처 Ver.2](docs/images/cloud-architecture-v2.png)

**Ver.3**

![클라우드 아키텍처 Ver.3](docs/images/cloud-architecture-v3.png)

**Ver.4(계획)**

![클라우드 아키텍처 Ver.4](docs/images/cloud-architecture-v4.png)

**Ver.5**

![클라우드 아키텍처 Ver.5](docs/images/cloud-architecture-v5.png)

</details>

### 2-3. 데이터베이스 ERD와 마이그레이션

테이블은 64개이고 Flyway 마이그레이션은 125개(V1~V128)입니다. 아래는 핵심 테이블만 고른 요약입니다. 실선은 외래 키, 점선은 외래 키 없이 `user_id` 컬럼으로 잇는 관계입니다.

```mermaid
erDiagram
    users ||--o{ social_accounts : "로그인 수단"
    users ||--o| credit_wallets : "지갑"
    users ||--o{ credit_transactions : "이프 원장"
    credit_transactions |o--o{ credit_lots : "적립 로트"
    users ||..o{ stories : "소유"
    stories ||--o{ story_characters : "인물"
    stories ||--o{ story_chats : "채팅"
    users ||..o{ story_chats : "진행"
    story_chats ||--o{ story_messages : "턴"
    story_messages }o--o| credit_transactions : "턴 차감"
    users ||--o{ device_push_tokens : "푸시 토큰"
    users ||--o{ user_notifications : "알림함"

    stories {
        uuid public_id
        varchar status
        varchar visibility
    }
    story_chats {
        uuid public_id
        int current_turn
    }
    push_outbox {
        varchar message_id
        varchar status
        varchar trace_parent
    }
```

스키마 문서는 [tbls](https://github.com/k1LoW/tbls)로 실제 DB에서 자동 생성합니다. CI(`db-docs.yml`)가 마이그레이션을 실제 PostgreSQL에 적용해 문서와 비교하고, 차이가 나면 PR을 막습니다. 전체 ERD와 테이블별 컬럼은 [dbdoc](dbdoc/README.md)에 있습니다.

### 2-4. API 문서화와 수동 검증

API는 앱용 93개, 관리자용 90개로 모두 183개 오퍼레이션입니다. 명세는 springdoc이 코드에서 OpenAPI로 생성하고, 관리자 웹은 이 명세에서 요청 타입을 생성해 씁니다. 내부 API(`/internal/**`)는 공유 시크릿으로 보호하고 공개 명세에서 뺐습니다.

엔드포인트를 추가하거나 계약을 바꾸면 통합 테스트와 함께 [`http/`](http) 아래 수동 검증 파일을 갱신합니다. 지금 84개 파일이 있고 IntelliJ HTTP Client로 바로 실행합니다. 파일마다 헬스 체크, 준비 요청, 핵심 검증, 오류 케이스 순서로 구성하고 응답 핸들러에서 상태 코드와 필드를 단언합니다.

### 2-5. 테스트

전체 테스트는 3,971개입니다(2026-11-20 실행 기준). 테스트의 기준은 하네스 스펙의 테스트 케이스 표입니다. 도메인마다 `5-5-TEST-CASES.md`가 확인 항목, 입력과 상황, 기대 결과를 행 단위로 적고, 테스트는 그 행의 입력과 기대 결과를 옮깁니다. 커버리지 수치 대신 스펙 행이 빠짐없이 테스트로 옮겨졌는지를 봅니다.

기본 테스트 프로파일은 H2 위에서 컨트롤러부터 저장까지를 통합 테스트로 확인합니다. 마이그레이션과 PostgreSQL 전용 동작, 검색은 Testcontainers로 실제 PostgreSQL(16, 17)과 OpenSearch를 띄워 확인합니다. AI 서버는 테스트용 클라이언트로, 분석 도구 같은 외부 API는 MockWebServer로 대신합니다.

테스트 실행 시간은 두 번에 걸쳐 줄였습니다.

| PR | 한 일 | 결과 |
| --- | --- | --- |
| [#480](https://github.com/KIM-N-KANG/manyak-server/pull/480) | 통합 테스트가 공용 베이스 클래스로 Spring 컨텍스트를 공유. 컨텍스트 조합 106개를 23개로 | 컨텍스트 기동 125회 → 23회, 전체 496초 → 367초 |
| [#481](https://github.com/KIM-N-KANG/manyak-server/pull/481) | 테스트 전용 재시도와 대기 단축, PostgreSQL 컨테이너를 JVM당 한 번만 띄우고 클래스마다 DB 복제 | 전체 369초 → 195초 |

### 2-6. 리팩터링

모듈 경계를 측정하는 테스트를 먼저 넣고(2026-11-10), 위반 수를 기준선으로 고정한 뒤 줄였습니다. 위반을 늘리는 변경은 실패하고, 줄이면 기준선도 함께 줄여야 통과하게 했습니다. 이틀 뒤 세 규칙 모두 0이 되었고, 지금은 기준선 없이 "위반이 하나라도 있으면 실패"로 바꿨습니다.

| 규칙 | 기준선([#482](https://github.com/KIM-N-KANG/manyak-server/pull/482), 11/10) | 최종([#539](https://github.com/KIM-N-KANG/manyak-server/pull/539), 11/12) |
| --- | --- | --- |
| R1 다른 모듈의 리포지토리, 엔티티 참조 | 268 | 0 |
| R2 모듈 간 순환 간선 | 47(간선 58개 중) | 0 |
| G `global`의 도메인 의존 | 22 | 0 |

옮길 때마다 기존 테스트를 먼저 통과시키고, 조회 수와 응답이 전후로 같은지 확인했습니다. 예를 들어 게스트 이관은 경계를 고친 뒤에도 쿼리 수가 18개 그대로입니다. 같은 기간에 스토리 목록 정렬에 커버링 인덱스를 넣어, 데이터를 10배와 100배로 늘린 실험에서 인기순 첫 페이지가 4.0~4.8배 빨라졌습니다([#507](https://github.com/KIM-N-KANG/manyak-server/pull/507)).

### 2-7. CI/CD 파이프라인

```mermaid
flowchart LR
    PR["PR"] --> T["테스트"]
    PR --> SEC["보안 스캔<br/>gitleaks, CodeQL"]
    PR --> DB["DB 문서 드리프트 검사<br/>tbls diff"]
    T --> B["이미지 빌드<br/>amd64, arm64"]
    B --> REG["레지스트리<br/>불변 태그 sha7"]
    REG --> G{"최신 커밋인가"}
    G -->|"예"| D["무중단 교체<br/>새 컨테이너 헬스 뒤 전환"]
    G -->|"아니오"| SKIP["최신 커밋 실행에 맡김"]
    D --> SM{"헬스와 스모크"}
    SM -->|"통과"| L["latest 승격"]
    SM -->|"실패"| RB["직전 이미지로 되돌리기"]

    classDef comp fill:#E8F2E8,stroke:#527A59,color:#182D1D
    classDef task fill:#F5D9C9,stroke:#B97450,color:#452B1D
    class T,SEC,DB,B,REG,D,L comp
    class RB,SKIP task
```

- dev에 머지하면 테스트 뒤 두 아키텍처 이미지를 만들어 dev 환경에 배포합니다. 릴리스 PR이 main에 머지되면 같은 트리의 dev 테스트 결과가 있으면 재사용하고 이미지만 다시 만듭니다.
- 배포 잡은 "이 실행의 커밋"이 아니라 "지금 main의 최신 커밋"을 배포합니다. 늦게 끝난 옛 빌드가 최신 배포를 덮지 않게 하려는 것입니다. `latest` 태그도 이 직렬 구간에서만 옮깁니다.
- 교체는 새 컨테이너가 헬스를 통과한 뒤 트래픽을 넘기고 옛 컨테이너를 드레인합니다. 운영 릴리스 두 번(v0.15.1, v0.15.2)에서 배포 내내 1초 간격으로 헬스를 불러 2,863번 중 실패가 0번이었습니다.
- dev와 main으로 가는 PR과 푸시마다 gitleaks로 그 범위의 커밋에서 비밀값 유출을 검사합니다. CodeQL(Kotlin, Actions, JavaScript, Python)은 PR과 dev 푸시, 매주 정기 실행으로 돌립니다.

### 2-8. 채팅 턴 스트리밍

```mermaid
sequenceDiagram
    participant C as 클라이언트
    participant S as manyak-server
    participant R as Redis
    participant A as manyak-ai
    participant D as PostgreSQL
    C->>S: 이어쓰기 요청(마지막 턴 ID)
    S->>R: 생성 중 키 획득(채팅당 하나)
    Note over S,R: 이미 있으면 거절, 마지막 턴이 다르면 거절
    S->>D: 스토리 스냅샷 읽기, 이프 선차감
    S->>A: 턴 생성(전용 실행기에서)
    loop 생성 중
        A-->>S: 토큰
        S->>R: Redis Streams에 이벤트 기록
        S-->>C: SSE 전달
    end
    Note over C,S: 연결이 끊겨도 생성은 계속
    C->>S: 재연결(생성 ID)
    S->>R: 처음부터 다시 읽어 전달
    S->>D: 턴 저장, 과금 확정
    S->>R: completed 기록, 키 해제
```

- **중복 턴 방지**: 채팅마다 Redis에 생성 중 키를 하나만 둡니다. 키가 있으면 새 요청을 거절하고, 요청의 마지막 턴 ID가 지금 마지막 턴과 다르면 거절합니다. 두 기기에서 동시에 보내도 턴이 겹치지 않습니다.
- **생성 중 이탈과 재연결**: 생성 작업과 전달 연결을 분리했습니다. 생성 작업은 모든 SSE 이벤트를 생성별 Redis Stream에 기록하고, 첫 연결과 재연결은 같은 스트림을 처음부터 읽습니다. 앱을 닫았다 열어도 생성 중인 턴을 이어 받고, 재연결은 새 AI 호출이나 과금을 만들지 않습니다.
- **과금 정합성**: 이프는 생성 전에 선차감하고, 턴 저장이 확정되면 과금을 확정합니다. 저장 전에 작업이 어떤 이유로든 끝나면 차감을 환불하고 체험 횟수를 복원합니다.

자세한 흐름은 하네스 [CHAT 백엔드 파이프라인](https://github.com/KIM-N-KANG/knk-harness/blob/dev/docs/domain/CHAT/BACKEND/4-1-PIPELINES.md)에 있습니다.

### 2-9. 알림 파이프라인

```mermaid
flowchart LR
    subgraph Server["manyak-server"]
        TX["도메인 트랜잭션<br/>아웃박스 행 기록"] --> RL["릴레이<br/>선점 후 일괄 발행"]
    end
    subgraph Noti["manyak-notification"]
        CL["처리 중 선점<br/>메시지 ID로 중복 제거"] --> EL["발송 자격 조회"]
        EL --> SEND["FCM 발송<br/>무효 토큰 정리"]
        SEND --> REP["결과 보고"]
    end
    RL -->|"추적 헤더를 메시지 속성에"| Q["SQS"]
    Q --> CL
    Q -.->|"반복 실패"| DLQ["DLQ"]
    EL -->|"내부 API"| TX
    REP -->|"내부 API"| TX

    classDef comp fill:#E8F2E8,stroke:#527A59,color:#182D1D
    classDef task fill:#F5D9C9,stroke:#B97450,color:#452B1D
    class TX,RL comp
    class CL,EL,SEND,REP task
```

- **아웃박스**: 스토리 완성 같은 도메인 변경과 같은 트랜잭션에 발송 요청 행을 기록합니다. 커밋된 변경만 알림 요청이 되고, 커밋 직후 서버가 멈춰도 요청 행은 남아 다음 릴레이가 보냅니다.
- **릴레이와 멱등**: 릴레이는 행을 선점하고 임대 시각을 둔 뒤 트랜잭션 밖에서 SQS로 일괄 발행합니다. 실패하면 백오프로 미루고 24시간이 지나면 포기로 기록합니다. 완료 기록 전 장애로 같은 메시지가 두 번 갈 수 있어, 알림 서비스가 메시지 ID로 처리 중 선점을 해 중복 발송을 막습니다.
- **트레이스 연결**: 요청 중에 기록한 행에는 그 요청의 추적 헤더를 저장하고, 발행할 때 큐 메시지 속성으로 넘깁니다. 알림 서비스의 발송 스팬이 원래 API 요청 트레이스에 이어져, 어느 요청이 어떤 푸시를 만들었는지 한 트레이스에서 봅니다.

### 2-10. 검색

```mermaid
flowchart LR
    Q["검색 요청"] --> OS{"OpenSearch 질의<br/>nori, 3초 한도"}
    OS -->|"성공"| CHK["DB 재검사<br/>지금 노출 조건"]
    CHK --> RES["응답"]
    OS -->|"실패, 시간 초과"| FB["DB 대체 검색"]
    FB --> RES
    EV["스토리 변경 이벤트"] -.->|"커밋 뒤 색인"| IDX[("stories 인덱스")]
    CRON["매일 04:30 재색인"] -.-> IDX

    classDef comp fill:#E8F2E8,stroke:#527A59,color:#182D1D
    class Q,OS,CHK,RES,FB comp
```

제목, 한 줄 소개, 장르, 인물 이름을 nori 분석기로 색인하고 제목에 가중치를 더 줍니다. 색인은 스토리 변경 이벤트를 커밋 뒤에 받아 갱신하고, 매일 새벽 전체를 다시 색인해 어긋난 문서를 바로잡습니다. 검색 결과는 DB에서 지금 노출 조건을 다시 확인해 비공개로 바뀐 스토리가 보이지 않게 합니다.

검색 저장소가 실패하거나 3초를 넘기면 기다리지 않고 DB에서 제목, 한 줄 소개, 장르를 찾아 응답합니다. 이때 다음 페이지 커서는 주지 않습니다.

### 2-11. 관측과 장애 진단

```mermaid
flowchart LR
    APPS["server, ai, notification"] -->|"OTLP"| COL["OTel Collector"]
    COL -->|"로그, 트레이스"| DP["Data Prepper"]
    DP --> OS[("OpenSearch")]
    COL -->|"메트릭"| AMP[("Managed Prometheus")]
    OS --> UI["OpenSearch UI"]
    AMP --> UI
    AMP -->|"1분 감시"| AG["진단 에이전트"]
    OS --> AG
    AG --> SL["Slack 보고"]

    classDef comp fill:#E8F2E8,stroke:#527A59,color:#182D1D
    classDef task fill:#F5D9C9,stroke:#B97450,color:#452B1D
    class APPS,COL,DP,UI comp
    class AG task
```

세 앱은 OpenTelemetry로 로그, 트레이스, 메트릭을 보냅니다. 요청 ID와 트레이스 ID가 로그에 함께 남아, 로그 한 줄에서 서버, AI, 알림 서비스까지의 트레이스로 바로 넘어갈 수 있습니다. 애플리케이션 오류는 Sentry, LLM 호출과 비용은 Langfuse, 사용자 행동은 Amplitude로 봅니다.

**진단 에이전트**는 장애 후보를 찾아 원인을 짚는 별도 서비스입니다. 1분마다 규칙으로 감시하고, 이상 후보가 3분 이어지면 LLM이 읽기 전용 도구로 트레이스, 로그, 메트릭을 조회해 진단합니다. 진단의 근거는 코드로 다시 검증한 뒤 Slack에 보고합니다. 부하 테스트에서 찾은 병목 4건을 재현해 평가했고, 3건은 두 번 모두 원인을 맞혔습니다. 남은 DB CPU 포화는 감지만 맞혔고, 보관 자료를 다시 흘린 평가에서는 다섯 번 모두 원인을 맞혔습니다. 진단 한 건의 비용은 $0.1~0.3입니다.

관리자 페이지에는 RDS, Redis, OpenSearch 같은 인프라 지표를 모아 보여 주는 운영 탭이 있습니다.

<img src="docs/images/admin-ops-storage.png" width="480" alt="관리자 운영 지표 저장소 탭(고정 데이터)">

### 2-12. 부하 테스트

운영과 같은 사양에 운영 DB 스냅샷을 복원한 별도 환경에서 측정했습니다(2026-10-08 ~ 11-03). LLM은 응답에 10.4초 걸리는 가짜 서버로 바꾸고, k6 가상 사용자는 실제 사용자처럼 32초에 한 턴을 보냅니다. 시나리오 코드는 [`loadtest/k6`](loadtest/k6)에 있습니다.

| 지표 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 동시 채팅 수용 인원 | 25명 | 약 390명 |
| 턴 p95(25 / 50 / 100 / 200명) | 25 / 68 / 70 / 69초 | 10.6 / 10.6 / 10.6 / 10.9초 |
| 100명 실패율 | 30% | 0% |

병목은 4건이었습니다. 가장 컸던 것은 채팅 턴 실행기의 실질 스레드가 4개뿐이라 턴이 큐에서 기다린 문제이고, 서버를 늘리지 않고 실행기 설정 하나로 풀었습니다(v0.7.3). 나머지는 동시 턴 상한, AI 서버 CPU 확장 기준, 목록 집계로 인한 DB CPU 포화였고, 앞의 둘은 부하 환경에서 수정안을 검증했습니다.

### 2-13. 비용 최적화

청구 상세 기준 하루 금액을 30일로 환산한 값입니다(USD, VAT 제외). 두 날 모두 두 가용 영역, Multi-AZ RDS, Redis 복제, NAT 2개의 이중화를 유지한 상태입니다.

| 항목 | 줄이기 전(11/09) | 줄인 뒤(11/15) |
| --- | --- | --- |
| 하루 합계 | 19.11 | 13.15 |
| 30일 환산 | 약 573 | 약 395 |
| ECS(Fargate) | 7.11 | 2.34 |
| EC2(NAT 등) | 2.91 | 2.93 |
| OpenSearch | 2.04 | 2.04 |
| RDS | 1.51 | 1.39 |
| ElastiCache | 1.20 | 1.16 |
| ALB | 1.09 | 1.08 |

하루 5.96달러, 약 31%를 줄였습니다. 이중화는 그대로 두고 다음을 바꿨습니다.

- 서비스별 CPU와 메모리 사용량을 보고 태스크 사양을 맞췄습니다. 예를 들어 server는 CPU p95가 40.5%여서 1 vCPU에서 0.5 vCPU로 줄였습니다.
- server와 ai는 온디맨드 태스크 1개를 기본으로 두고 나머지를 Fargate Spot으로 돌립니다.
- 앱 태스크를 Graviton(ARM64)으로 옮기고 RDS와 Redis도 t4g로 바꿨습니다. 이미지는 두 아키텍처로 빌드합니다.

## 3. 수행 방법

### 3-1. 스펙 주도 개발

기능은 [knk-harness](https://github.com/KIM-N-KANG/knk-harness)의 도메인 스펙에서 시작합니다. 회원, 스토리, 채팅, 결제, 알림, 공지, 검수, 관리자 8개 도메인마다 요구사항, 소프트웨어 구조, DB 스키마, 파이프라인, API 계약, 보안, 동시성, 오류 처리, 관측, 테스트 케이스를 문서로 둡니다. 하네스의 도메인 문서는 383개입니다.

스펙이 단일 정본입니다. 코드와 스펙이 다르면 코드를 스펙에 맞추고, 스펙이 모호하면 구현 전에 정합니다. 설계를 바꿀 때는 스펙을 먼저 고치고, 그 변경을 따라 코드와 테스트를 고칩니다. 선택의 이유는 도메인별 `DECISIONS.md`에 남깁니다.

### 3-2. 형상 관리와 릴리스

```mermaid
gitGraph
    commit id: "v0.15.1" tag: "v0.15.1"
    branch dev
    checkout dev
    commit id: "기능 PR (Squash)"
    commit id: "기능 PR (Squash) "
    branch release/v0.15.2
    checkout main
    merge release/v0.15.2 id: "릴리스 PR (Merge Commit)" tag: "v0.15.2"
```

기능 브랜치는 최신 `dev`에서 따고 Squash로 머지합니다. 릴리스는 `dev`에서 `release/vX.Y.Z`를 따서 `main`에 Merge Commit으로 머지하고 태그를 붙입니다. `main` 머지가 운영 배포를 시작합니다. 릴리스 PR에는 포함 PR과 함께 이미지 배포만으로 끝나지 않는 "코드 외 할 일"을 적고, 그 항목이 끝나야 릴리스를 닫습니다.

브랜치, 커밋, PR 제목은 Jira 티켓 키로 묶습니다(`[KNK-123] Feat: ...`). 2026-06-25 v0.1.0부터 2026-11-20 v0.15.2까지 태그 78개를 붙였고, 병합한 PR은 562개입니다.

### 3-3. AI 에이전트 협업

개발에는 LLM 에이전트를 적극적으로 씁니다. 계획과 검수를 맡는 에이전트가 작업 지시서를 쓰고, 구현을 맡는 에이전트가 브랜치, 커밋, 테스트, PR까지 진행합니다. 둘 다 같은 하네스의 규칙과 스킬(브랜치, 커밋, PR, 리뷰 절차)을 따릅니다.

작업의 기준은 스펙과 테스트 행입니다. 구현 에이전트는 스펙의 테스트 행을 먼저 테스트로 옮기고, PR 전에 다른 모델로 로컬 코드 리뷰를 돌리며, CI가 통과해야 머지합니다. 머지와 운영 릴리스는 사람이 승인합니다.

## 4. 개발 가이드

로컬 실행, 환경 변수, 엔드포인트, 스키마 문서 생성 방법은 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)에 있습니다.
