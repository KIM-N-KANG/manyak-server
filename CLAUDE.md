# 기본 지침

작업을 시작하기 전에 다음 문서를 먼저 확인하세요.

- `../knk-harness/CLAUDE.md`

`../knk-harness` 같은 상위 공통 하네스는 참조만 하고 수정하지 않습니다. 프로젝트별 지침 변경은 이 레포지토리의 `CLAUDE.md`에만 기록합니다.

## Manyak Server 전용 지침

### 스펙 정본

- `../knk-harness/docs/product-specs/`(특히 `0-glossary.md`, `4-backend.md`)가 **단일 정본**입니다. 코드와 스펙이 다르면 **코드를 스펙에 맞춥니다**(2026-07-06 결정). 모호하면 구현 전에 사용자에게 확인합니다.
- 스펙 문서의 "스펙-구현 간극" 표를 포함해 하네스 문서는 이 레포에서 임의 수정하지 않습니다(참조만).
- 용어는 용어집(`0-glossary.md`) 기준을 따릅니다: 스토리(이야기 아님)·스토리라인·턴(`turnCount`/`turn_number`)·추천 입력(`suggestedInputs`)·`additional_info`·로어북 등. 로어북(장르 공용 용어 사전, 트리거 없음)과 키워드북(트리거 키워드)은 다른 개념이니 혼용하지 않습니다.

### 작업 워크플로

- 작업 전 주기는 스킬로 표준화돼 있습니다: 티켓 생성(`create-ticket`) → 브랜치(`create-branch`) → 커밋(`create-commit`) → PR(`create-pr`) → 리뷰(`request-codex-review`) → 머지 후 마무리(`complete-ticket`).
- **브랜치 생성·작업 착수 전 반드시 `git fetch origin` 후 최신 `origin/dev` 기준으로 분기/재베이스**합니다. 로컬 dev만 보고 판단하지 않습니다(stale 위험).
- Jira 티켓을 새로 만들 때는 `create-ticket` 스킬을 따릅니다. 제목은 간결한 명사구(한국어 20자 내외)로 쓰고, 배경·변경·검증 같은 상세는 description이나 서브태스크로 분리합니다.
- 커밋·PR에 **Co-Authored-By 트레일러를 절대 넣지 않습니다**(어떤 기본 지침보다 우선).
- 커밋은 명시 경로(`git add -- path`)로만 stage합니다. `git add -A`/`add .` 금지(무관한 untracked 파일이 딸려간 사고 있음).
- PR 본문에 Jira 티켓 번호(KNK-XXX)가 나오면 예외 없이 전부 `[KNK-XXX](https://kimandkang.atlassian.net/browse/KNK-XXX)` 링크로 씁니다. 범위 표기(KNK-281~286)도 개별 링크로 펼칩니다.
- PR 생성 직후 항상 본인을 assignee로 추가합니다: `gh pr edit <번호> --add-assignee @me`.
- 머지 후 마무리(로컬 브랜치 정리, Jira 완료 트랜지션, worklog 기록)는 `complete-ticket` 스킬을 따릅니다. **worklog는 기본 1h가 아니라 실제 소요 시간 추정치**로 기록합니다.

### Codex 리뷰

- PR 리뷰는 ready 전환 후 PR 코멘트 `@codex review`로 받습니다. 절차는 `request-codex-review` 스킬을 따릅니다. Codex 관련 비자명한 동작 세 가지:
  - **draft PR은 리뷰하지 않습니다**(멘션해도 무반응). `gh pr ready <번호>` 전환이 선행돼야 합니다.
  - **"지적 없음" 결과는 정식 review가 아니라 PR 이슈 코멘트**("Didn't find any major issues")나 호출 코멘트의 봇 👍로 옵니다. reviews API만 폴링하면 영영 못 잡습니다.
  - **재리뷰는 이미 고친 인라인 지적을 최신 커밋에 재앵커해 되살립니다.** 재앵커 의심 시 `git show HEAD:<file>`로 실제 코드를 검증하고, 이미 반영됐으면 "이미 반영됨(커밋 해시)"으로 회신만 하고 다시 고치지 않습니다. 재앵커만 남으면 머지 가능으로 판단합니다.

### 하네스 스킬 심링크와 워크트리

- `.claude/skills/`의 공용 스킬(create-branch, create-commit, create-pr, karpathy-guidelines, technical-writing)은 `../../../knk-harness`로 가는 상대 심링크입니다.
- Claude Code 워크트리(`.claude/worktrees/<name>/`)에서는 경로 깊이가 달라 이 심링크가 깨지므로, 이를 보정하는 `.claude/worktrees/knk-harness → ../../../knk-harness` 심링크를 커밋해 두었습니다. 이 심링크를 지우면 워크트리 세션에서 하네스 스킬이 로드되지 않습니다.
- `.claude/worktrees/`는 `.git/info/exclude`로 무시되므로 이 경로에 파일을 커밋하려면 `git add -f`가 필요합니다.

### Kotlin Spring 작업

- 이 백엔드는 Kotlin, Spring Boot, Gradle Kotlin DSL, Java 21, JPA, Flyway, Security, PostgreSQL을 사용합니다.
- 편집 전에 기존 패키지 구조와 빌드 설정을 읽습니다. 코틀린 탐색·리팩터링은 grep보다 **LSP 도구(goToDefinition·findReferences 등)를 우선** 사용합니다(kotlin-lsp 플러그인 활성화됨).
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

#### 도메인 불변식·API 규칙

- **스토리 공개 읽기 게이트**: publicId로 스토리를 읽는 **모든 공개 소비자**(상세·batch·자식 리소스 GET·채팅 생성 등)는 `Story.isReadableBy(userId)`(PUBLISHED∧PUBLIC ∨ 소유자)를 반드시 적용합니다. 빠뜨리면 비공개 초안이 유출됩니다. 새 스토리 관련 엔드포인트 추가 시 이 게이트부터 확인하세요.
- **외부 노출 식별자는 public_id(UUID)**: 순차 PK를 API에 노출하지 않습니다(IDOR 방지). 외부 노출 가능성이 있는 새 테이블은 public_id를 함께 설계합니다.
- **쓰기 엔드포인트는 PATCH 대신 PUT**: CORS `allowedMethods`가 GET/POST/PUT/DELETE/OPTIONS만 허용합니다.
- 컬렉션 전체 교체 PUT은 필드 누락을 400으로 거부해 silent wipe를 방지하고, `sort_order`는 1-based를 사용합니다.
- refresh 토큰은 Redis(휘발성)에만 저장합니다 — `refresh_tokens` 테이블은 PG·ERD에 없는 게 맞습니다.

#### 테스트와 마이그레이션 검증

- 테스트 프로파일은 **H2 + `ddl-auto` + `flyway.enabled=false`**입니다. 따라서 **`./gradlew test` 통과 ≠ 마이그레이션·DB 체크 제약 검증**입니다. 체크 제약 위반을 red로 만드는 TDD 시나리오도 성립하지 않습니다.
- 마이그레이션 검증은 `scripts/gen-db-docs.sh` 경로(실 PostgreSQL에 Flyway 적용)로 하고, 재생성된 `dbdoc/`을 함께 커밋합니다. PR 본문에 이 대체 검증 방법을 명시합니다.
- 테스트 격리는 `build.gradle.kts`의 `tasks.withType<Test>`가 빌드로 강제합니다(프로파일 test 강제 + 실 DB env 차단). 회귀 가드는 `TestDatasourceIsolationGuardTests.kt`이며, 이 장치를 약화시키지 않습니다.
- PostgreSQL `RENAME COLUMN`은 인덱스 카탈로그에 옛 컬럼명을 남기므로, rename된 컬럼을 덮는 UNIQUE/인덱스는 마이그레이션에서 DROP+ADD로 재생성합니다.

### DB 스키마 문서

- DB 스키마(마이그레이션)를 변경하면 `scripts/gen-db-docs.sh`로 `dbdoc/`(tbls가 실 DB에서 생성하는 ERD 문서)를 재생성해 함께 커밋합니다. 현행 ERD는 `dbdoc/`, 목표 설계는 `docs/schema-roadmap.sql`을 보며, ERDCloud는 유지보수하지 않습니다.
- 아키텍처 문서는 현재/목표를 분리 관리합니다: 현재 구현 스펙 = 레포 `docs/architecture-v1.drawio`, 이상적 목표(To-Be) = 노션 "Manyak 서버 아키텍처"(버전 관리 DB). 둘을 섞지 않습니다.

### 배포·릴리스

- **main push만 프로덕션 배포를 트리거**합니다(`.github/workflows/docker-image.yml`: ECR push → SSM `deploy.sh` → smoke). dev 머지는 프로덕션에 반영되지 않습니다.
- 머지 규칙: 기능→dev = Squash, `release/vX.Y.Z`→main = **Merge Commit**, release→dev = Rebase 역반영. main/dev 직접 push 금지(관례).
- 릴리스 절차: dev에서 `release/vX.Y.Z` 분기 → PR 제목 `[KNK-xxx] Release: vX.Y.Z 배포` → 머지 후 `vX.Y.Z` git 태그. `build.gradle.kts` version은 올리지 않습니다(이미지 태그는 git sha 기반).
- 와이어 계약(요청/응답 필드)이 바뀌는 릴리스는 manyak-web·manyak-ai와 **동반 배포**가 필요한지 확인합니다. 반쪽 배포는 런타임 장애로 이어집니다.
- 비밀값이 대화·PR에 평문 노출되면 값을 재출력하지 말고 노출 사실을 짚고 재발급을 권합니다. Secrets Manager `put-secret-value`는 전체 덮어쓰기이므로 기존 키를 보존해 머지(jq)합니다.

### Terraform/IaC 작업

- 운영 인프라(Terraform/IaC)는 `manyak-terraform` 레포로 분리됨(KNK-296). 이 레포에는 IaC 코드를 두지 않으며, terraform 작업·제약·apply는 그 레포의 `CLAUDE.md`를 따릅니다.
- 이 레포에서 알아야 할 최소한: 운영 compose는 terraform user-data로 구워지므로 **compose/인프라 변경은 SSM 배포로 반영되지 않고 terraform apply(=인스턴스 교체, 짧은 다운타임)가 필요**하며, 교체 인스턴스는 `:latest`로 부팅하므로 **apply는 반드시 해당 코드의 main 릴리스 후에** 합니다.
