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

`docker compose`는 현재 디렉터리의 `.env` 파일을 자동으로 읽습니다. Spring Boot를 IntelliJ에서 실행할 때는 Run Configuration의 Environment variables에 값을 직접 넣습니다.

필요한 환경변수는 다음과 같습니다.

```bash
MANYAK_DB_URL=jdbc:postgresql://localhost:<MANYAK_DB_PORT>/<MANYAK_DB_NAME>
MANYAK_DB_USERNAME=<DB 사용자>
MANYAK_DB_PASSWORD=<DB 비밀번호>
MANYAK_AI_BASE_URL=http://localhost:8000
MANYAK_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://192.168.0.12:3000
MANYAK_SLACK_FEEDBACK_WEBHOOK_URL=https://hooks.slack.com/services/T000/B000/XXXX
MANYAK_ANALYTICS_ANONYMOUS_ID_PEPPER=optional-random-pepper
```

`MANYAK_AI_BASE_URL`에는 반드시 `http://` 또는 `https://` scheme까지 포함합니다.
`MANYAK_CORS_ALLOWED_ORIGINS`는 쉼표로 구분하며, 각 origin에는 scheme과 port를 포함합니다.
`MANYAK_SLACK_FEEDBACK_WEBHOOK_URL`은 선택값입니다. 설정하면 피드백 등록 시 해당 Slack Incoming Webhook으로 알림을 보내고, 비워 두면 알림을 건너뜁니다(등록은 정상 동작).
`MANYAK_ANALYTICS_ANONYMOUS_ID_PEPPER`은 선택값입니다. 익명 ID 해시에 섞는 pepper로, 설정하면 ID 추측 공격을 완화하고 비워 두면 무염 해시를 사용합니다.

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
- 서버 레포 LLM 지침: `CLAUDE.md`
