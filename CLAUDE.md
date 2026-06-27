# 기본 지침

작업을 시작하기 전에 다음 문서를 먼저 확인하세요.

- `../knk-harness/CLAUDE.md`

`../knk-harness` 같은 상위 공통 하네스는 참조만 하고 수정하지 않습니다. 프로젝트별 지침 변경은 이 레포지토리의 `CLAUDE.md`에만 기록합니다.

## Manyak Server 전용 지침

### 작업 워크플로

- 작업 전 주기는 스킬로 표준화돼 있습니다: 티켓 생성(`create-ticket`) → 브랜치(`create-branch`) → 커밋(`create-commit`) → PR(`create-pr`) → 리뷰(`request-codex-review`) → 머지 후 마무리(`complete-ticket`).
- Jira 티켓을 새로 만들 때는 `create-ticket` 스킬을 따릅니다. 제목은 간결한 명사구로 쓰고, 배경·변경·검증 같은 상세는 description이나 서브태스크로 분리합니다.
- PR 리뷰는 ready 전환 후 PR 코멘트 `@codex review`로 받습니다. ready 전환·호출·리뷰 대기·반영은 `request-codex-review` 스킬을 따릅니다(Codex는 draft PR을 리뷰하지 않습니다).
- 머지 후 마무리(로컬 브랜치 정리, Jira 완료 트랜지션, 작업시간 worklog 기록)는 `complete-ticket` 스킬을 따릅니다.

### Kotlin Spring 작업

- 이 백엔드는 Kotlin, Spring Boot, Gradle Kotlin DSL, Java 21, JPA, Flyway, Security, PostgreSQL을 사용합니다.
- 편집 전에 기존 패키지 구조와 빌드 설정을 읽습니다.
- 기능 구현이나 버그 수정처럼 동작 변경이 있는 작업은 기본적으로 TDD를 수행합니다.
- 먼저 실패하는 테스트로 기대 동작을 고정한 뒤, 최소 구현으로 통과시키고 필요한 리팩터링을 진행합니다.
- 기존 테스트만으로 충분한 경우에도 그 근거를 확인하고, 누락된 경계 조건이나 회귀 위험이 있으면 테스트를 먼저 보강합니다.
- TDD를 적용하기 어려운 작업이면 작업 전 또는 완료 보고에서 이유와 대체 검증 방법을 명확히 설명합니다.
- 보일러플레이트 변경은 작고 단순하게 유지합니다. 프로젝트에 명확한 이유가 없다면 Spring Boot 기본값을 우선합니다.
- 비즈니스 API prefix는 `/api/v1`을 사용합니다.
- 운영 상태 확인에는 Spring Boot Actuator를 사용합니다. 구체적인 제품 또는 인프라 요구사항이 없다면 별도 health endpoint를 추가하지 않습니다.
- 외부에서 설정 가능한 값은 Spring 설정 또는 환경변수로 둡니다. 실제 비밀값은 커밋하지 않습니다.
- JPA, Flyway, Security, datasource, API 동작을 변경할 때는 완료 보고 전에 관련 Gradle 검증을 실행합니다.
- 로컬 환경 제한으로 검증을 실행할 수 없다면 정확한 차단 사유와 실행해야 할 명령을 설명합니다.
- API 엔드포인트를 추가하거나 동작을 변경하면 통합 테스트와 함께 `http/` 디렉터리에 수동 검증용 `.http` 파일을 항상 작성하거나 갱신합니다. 작성 방법과 IntelliJ 호환 규칙은 `create-http-verification` 스킬을 따릅니다.
- DB 스키마(마이그레이션)를 변경하면 `scripts/gen-db-docs.sh`로 `dbdoc/`(tbls가 실 DB에서 생성하는 ERD 문서)를 재생성해 함께 커밋합니다. 현행 ERD는 `dbdoc/`, 목표 설계는 `docs/schema-roadmap.sql`을 보며, ERDCloud는 유지보수하지 않습니다.

### Terraform/IaC 작업

- 운영 인프라(Terraform/IaC)는 `manyak-terraform` 레포로 분리됨(KNK-296). 이 레포에는 IaC 코드를 두지 않으며, terraform 작업·제약·apply는 그 레포의 `CLAUDE.md`를 따릅니다.
