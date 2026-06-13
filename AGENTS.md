## 기본 지침

작업을 시작하기 전에 먼저 아래 문서를 읽습니다.
- `../knk-harness/AGENTS.md`

## Manyak Server 전용 지침

### Kotlin Spring 작업

- 이 백엔드는 Kotlin, Spring Boot, Gradle Kotlin DSL, Java 21, JPA, Flyway, Security, PostgreSQL을 사용합니다.
- 편집 전에 기존 패키지 구조와 빌드 설정을 읽습니다.
- 보일러플레이트 변경은 작고 단순하게 유지합니다. 프로젝트에 명확한 이유가 없다면 Spring Boot 기본값을 우선합니다.
- 비즈니스 API prefix는 `/api/v1`을 사용합니다.
- 운영 상태 확인에는 Spring Boot Actuator를 사용합니다. 구체적인 제품 또는 인프라 요구사항이 없다면 별도 health endpoint를 추가하지 않습니다.
- 외부에서 설정 가능한 값은 Spring 설정 또는 환경변수로 둡니다. 실제 비밀값은 커밋하지 않습니다.
- JPA, Flyway, Security, datasource, API 동작을 변경할 때는 완료 보고 전에 관련 Gradle 검증을 실행합니다.
- 로컬 환경 제한으로 검증을 실행할 수 없다면 정확한 차단 사유와 실행해야 할 명령을 설명합니다.
