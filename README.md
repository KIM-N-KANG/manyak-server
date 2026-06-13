# manyak-server

Manyak 백엔드 서버입니다. Kotlin, Spring Boot, Java 21, Gradle, JPA, Flyway, Security, PostgreSQL을 사용합니다.

## 로컬 실행

PostgreSQL을 실행합니다.

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

`docker compose`는 현재 디렉터리의 `.env` 파일을 자동으로 읽습니다. IntelliJ 실행 설정에 입력한 환경변수는 IntelliJ로 실행할 때만 적용됩니다.

터미널에서 서버를 실행할 때는 `.env` 값을 셸 환경변수로 먼저 로드합니다.

```bash
set -a
source .env
set +a
./gradlew bootRun
```

실제 비밀번호나 로컬 전용 설정 파일은 커밋하지 않습니다.

## 엔드포인트

- Actuator 상태 확인: `GET /actuator/health`
- Actuator 활성 상태: `GET /actuator/health/liveness`
- Actuator 준비 상태: `GET /actuator/health/readiness`
- OpenAPI JSON: `GET /v3/api-docs`
- Swagger UI: `GET /swagger-ui.html`

비즈니스 API는 `/api/v1` prefix를 사용합니다.

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
- 서버 레포 LLM 지침: `AGENTS.md`
