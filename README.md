# manyak-server

Manyak backend server built with Kotlin, Spring Boot, Java 21, Gradle, JPA, Flyway, Security, and PostgreSQL.

## Local Setup

Start PostgreSQL:

```bash
docker compose up -d
```

Run the server:

```bash
./gradlew bootRun
```

Run tests:

```bash
./gradlew test
```

## Environment

Local defaults are defined in `application-local.yml`. Override them with environment variables when needed:

```bash
MANYAK_DB_URL=jdbc:postgresql://localhost:5432/manyak
MANYAK_DB_USERNAME=manyak
MANYAK_DB_PASSWORD=manyak
```

Use `.env.example` as a template. Do not commit real secrets.

## Endpoints

- Actuator health: `GET /actuator/health`
- Actuator liveness: `GET /actuator/health/liveness`
- Actuator readiness: `GET /actuator/health/readiness`
- OpenAPI JSON: `GET /v3/api-docs`
- Swagger UI: `GET /swagger-ui.html`

Business APIs should use the `/api/v1` prefix.

## Team Wiki

The team LLM wiki is cloned locally into `wiki/` and ignored by this repository.

```bash
git clone https://github.com/KIM-N-KANG/llm-wiki.git wiki
git -C wiki pull
```
