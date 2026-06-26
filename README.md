# manyak-server

Manyak 백엔드 서버입니다. Kotlin, Spring Boot, Java 21, Gradle, JPA, Flyway, Security, PostgreSQL을 사용합니다.

## 로컬 실행

PostgreSQL과 Redis를 실행합니다.

```bash
docker compose up -d
```

서버를 실행합니다.

```bash
./gradlew bootRun
```

테스트를 실행합니다.

```bash
./gradlew test
```

## 환경변수

로컬 실행에도 DB 접속 환경변수가 필요합니다. `.env.example`에 필요한 변수 목록이 정리되어 있습니다.

`docker compose`는 현재 디렉터리의 `.env` 파일을 자동으로 읽습니다. Spring Boot를 IntelliJ에서 실행할 때는 Run Configuration의 Environment variables에 값을 직접 넣습니다.

필요한 환경변수는 다음과 같습니다.

```bash
MANYAK_DB_URL=jdbc:postgresql://localhost:<MANYAK_DB_PORT>/<MANYAK_DB_NAME>
MANYAK_DB_USERNAME=<DB 사용자>
MANYAK_DB_PASSWORD=<DB 비밀번호>
MANYAK_REDIS_PORT=6379
MANYAK_AI_BASE_URL=http://localhost:8000
MANYAK_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://192.168.0.12:3000
MANYAK_SLACK_FEEDBACK_WEBHOOK_URL=https://hooks.slack.com/services/T000/B000/XXXX
MANYAK_ANALYTICS_ANONYMOUS_ID_PEPPER=optional-random-pepper
```

`MANYAK_AI_BASE_URL`에는 반드시 `http://` 또는 `https://` scheme까지 포함합니다.
`MANYAK_CORS_ALLOWED_ORIGINS`는 쉼표로 구분하며, 각 origin에는 scheme과 port를 포함합니다.
`MANYAK_SLACK_FEEDBACK_WEBHOOK_URL`은 선택값입니다. 설정하면 피드백 등록 시 해당 Slack Incoming Webhook으로 알림을 보내고, 비워 두면 알림을 건너뜁니다(등록은 정상 동작).
`MANYAK_ANALYTICS_ANONYMOUS_ID_PEPPER`은 선택값입니다. 익명 ID 해시에 섞는 pepper로, 설정하면 ID 추측 공격을 완화하고 비워 두면 무염 해시를 사용합니다.
`MANYAK_REDIS_PORT`은 로컬 Redis 컨테이너가 게시할 포트입니다(기본 `6379`). 앱은 기본적으로 `localhost:6379`에 연결하며, `SPRING_DATA_REDIS_HOST`/`SPRING_DATA_REDIS_PORT`로 재정의합니다. 운영에서는 ElastiCache 엔드포인트를 주입합니다.

실제 비밀번호나 로컬 전용 설정 파일은 커밋하지 않습니다.

## 엔드포인트

- Actuator 상태 확인: `GET /actuator/health`
- Actuator 활성 상태: `GET /actuator/health/liveness`
- Actuator 준비 상태: `GET /actuator/health/readiness`
- OpenAPI JSON: `GET /v3/api-docs`
- Swagger UI: `GET /swagger-ui.html`

비즈니스 API는 `/api/v1` prefix를 사용합니다.

## 스키마 문서

DB 스키마 문서는 [tbls](https://github.com/k1LoW/tbls)로 **실 DB에서 자동 생성**합니다. Flyway 마이그레이션이 진실원본이고, 문서는 거기서 파생됩니다.

- **현행 스키마**: `dbdoc/` (tbls 자동 생성, ER 다이어그램 포함). 손으로 고치지 않습니다.
- **목표(설계) 스키마**: `docs/schema-roadmap.sql` (수기, 새 기능 설계 시 갱신). ERDCloud는 유지보수를 중단했습니다.

스키마(마이그레이션)를 바꾸면 문서를 재생성해 함께 커밋합니다.

```bash
brew install tbls          # 최초 1회
./scripts/gen-db-docs.sh   # Docker 실행 + .env 필요
```

> 최초 도입 시 `dbdoc/` 베이스라인은 Docker가 켜진 환경에서 위 스크립트를 1회 실행해 생성·커밋해야 합니다. 이후 `tbls diff` CI(`.github/workflows/db-docs.yml`)가 마이그레이션 변경 시 문서 드리프트를 차단합니다.

## 팀 위키

팀 LLM 위키와 공통 하네스는 manyak-server와 같은 상위 디렉터리에 둡니다.

```bash
(
  cd ..
  git clone https://github.com/KIM-N-KANG/llm-wiki.git
  git clone https://github.com/KIM-N-KANG/knk-harness.git
  git -C llm-wiki pull
  git -C knk-harness pull
)
```

- 팀 위키: `../llm-wiki`
- 팀 LLM 하네스: `../knk-harness`
- 서버 레포 LLM 지침: `CLAUDE.md`
