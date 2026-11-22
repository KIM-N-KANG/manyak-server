# manyak-server 작업 지침

이 파일이 이 레포 지침의 정본입니다. Claude Code와 Codex 모두 이 파일을 읽습니다(`CLAUDE.md`는 두지 않습니다). 지침을 바꿀 때는 이 파일만 고칩니다.

## 기본 지침

- 작업을 시작하기 전에 `../knk-harness/AGENTS.md`를 먼저 확인합니다.
- `../knk-harness` 같은 상위 공통 하네스는 참조만 하고 이 레포에서 임의로 수정하지 않습니다. 프로젝트별 지침 변경은 이 레포의 `AGENTS.md`에만 기록합니다.

## 스펙 정본

- 하네스 [용어집](../knk-harness/docs/reference/GLOSSARY.md)과 [도메인 목록](../knk-harness/docs/spec/AGENTS.md)의 BACKEND 명세와 AI 계약이 **단일 정본**입니다. 작업과 리뷰 전에 해당 계약을 확인합니다. 코드와 스펙이 다르면 **코드를 스펙에 맞춥니다**(2026-07-06 결정). 모호하면 구현 전에 사용자에게 확인합니다.
- 현재 모듈, 저장소, 동시성, 운영 연결은 `../knk-harness/docs/platform/BACKEND/AGENTS.md`, 중요한 선택 이유는 `../knk-harness/docs/reference/decisions/BACKEND.md`를 참조합니다.
- 용어는 [용어집](../knk-harness/docs/reference/GLOSSARY.md)을 따릅니다. 스토리(이야기 아님), 스토리라인, 턴(`turnCount`/`turn_number`), 추천 입력(`suggestedInputs`), `additional_info`, 로어북 등입니다. 로어북(장르 공용 용어 사전, 트리거 없음)과 키워드북(트리거 키워드)은 다른 개념이니 혼용하지 않습니다.

## 작업 워크플로

- 작업 전 주기는 스킬로 표준화돼 있습니다: 티켓 생성(`create-ticket`) → 브랜치(`create-branch`) → 커밋(`create-commit`) → PR(`create-pr`) → 리뷰(`request-codex-review`) → 머지 후 마무리(`complete-ticket`).
- **브랜치 생성과 작업 착수 전에 반드시 `git fetch origin` 후 최신 `origin/dev` 기준으로 분기하거나 재베이스**합니다. 로컬 dev만 보고 판단하지 않습니다(stale 위험).
- Jira 티켓을 새로 만들 때는 `create-ticket` 스킬을 따릅니다. 제목은 간결한 명사구(한국어 20자 내외)로 쓰고, 배경, 변경, 검증 같은 상세는 description이나 서브태스크로 분리합니다.
- 작은 설정 변경이라도 "다음 릴리스에 같이"로 정하는 순간 티켓을 만듭니다.
- 커밋과 PR에 **Co-Authored-By 트레일러를 절대 넣지 않습니다**(어떤 기본 지침보다 우선).
- 커밋은 명시 경로(`git add -- path`)로만 stage합니다. `git add -A`, `git add .`는 금지합니다(무관한 untracked 파일이 딸려간 사고가 있었습니다).
- PR 본문에 Jira 티켓 번호(KNK-XXX)가 나오면 예외 없이 전부 `[KNK-XXX](https://kimandkang.atlassian.net/browse/KNK-XXX)` 링크로 씁니다. 범위 표기(KNK-281\~286)도 개별 링크로 펼칩니다.
- PR 생성 직후 항상 본인을 assignee로 추가합니다: `gh pr edit <번호> --add-assignee @me`.
- 머지 후 마무리(로컬 브랜치 정리, Jira 완료 트랜지션, worklog 기록)는 `complete-ticket` 스킬을 따릅니다. **worklog는 기본 1h가 아니라 실제 소요 시간 추정치**로 기록합니다.

## Codex 리뷰

### 리뷰 쓰는 법

- 리뷰 코멘트와 리뷰 요약은 **한국어**로 작성합니다. 코드 식별자, 파일 경로, 로그, 오류 메시지는 원문을 유지합니다.
- 단순 스타일이나 포매팅 문제는 리뷰하지 않습니다.
- 이 파일의 규칙을 근거로 리뷰합니다. 특히 아래 "도메인 불변식과 API 규칙", "테스트와 마이그레이션 검증"은 이 레포에서 자주 오독되는 부분입니다.

### PR 리뷰 받는 법

- 최초 PR 리뷰는 **`gh pr ready <번호>` 전환만으로 자동으로** 달립니다. 절차는 `request-codex-review` 스킬을 따르되 **최초 호출의 멘션 단계는 건너뜁니다**. Codex 관련 비자명한 동작 다섯 가지:
  - **최초 리뷰에는 `@codex review` 멘션을 달지 않습니다.** ready 전환으로 이미 자동 리뷰가 돌기 때문에 중복이 아니라 해롭습니다. 멘션은 리뷰 쿼터를 태우고 `You have reached your Codex usage limits for code reviews`로 거절돼 "리뷰 실패"로 오독하게 만듭니다(2026-08-14 PR #185에서 거절 코멘트와 별개로 자동 리뷰는 5분 뒤 정상 도착).
  - **추가 리뷰와 재리뷰는 `@codex review` 멘션이 유일한 트리거입니다.** 지적을 반영해 커밋을 push해도 재리뷰는 돌지 않습니다(push는 트리거 목록에 없음). 최초 자동 리뷰 이후 다시 봐야 하면 그때는 멘션을 남깁니다.
  - **draft PR은 리뷰하지 않습니다**(멘션해도 무반응). `gh pr ready <번호>` 전환이 선행돼야 합니다.
  - **"지적 없음" 결과는 정식 review가 아니라 PR 이슈 코멘트**("Didn't find any major issues")나 호출 코멘트의 봇 👍로 옵니다. reviews API만 폴링하면 영영 못 잡습니다.
  - **재리뷰는 이미 고친 인라인 지적을 최신 커밋에 재앵커해 되살립니다.** 재앵커가 의심되면 `git show HEAD:<file>`로 실제 코드를 검증하고, 이미 반영됐으면 "이미 반영됨(커밋 해시)"으로 회신만 하고 다시 고치지 않습니다. 재앵커만 남으면 머지 가능으로 판단합니다.

## 하네스 스킬 심링크와 워크트리

- `.claude/skills/`의 공용 스킬(create-branch, create-commit, create-pr, karpathy-guidelines, technical-writing)은 `../../../knk-harness`로 가는 상대 심링크입니다.
- Claude Code 워크트리(`.claude/worktrees/<name>/`)에서는 경로 깊이가 달라 이 심링크가 깨지므로, 이를 보정하는 `.claude/worktrees/knk-harness → ../../../knk-harness` 심링크를 커밋해 두었습니다. 이 심링크를 지우면 워크트리 세션에서 하네스 스킬이 로드되지 않습니다.
- `.claude/worktrees/`는 `.git/info/exclude`로 무시되므로 이 경로에 파일을 커밋하려면 `git add -f`가 필요합니다.

## Kotlin Spring 작업

- 이 백엔드는 Kotlin, Spring Boot, Gradle Kotlin DSL, Java 25, JPA, Flyway, Security, PostgreSQL을 사용합니다.
- **Jackson 3를 씁니다.** databind는 `tools.jackson.databind.*`(ObjectMapper, JsonNode)이고, 애노테이션만 `com.fasterxml.*`입니다. `com.fasterxml.jackson.databind`를 임포트하면 컨텍스트 로드가 깨집니다.
- 편집 전에 기존 패키지 구조와 빌드 설정을 읽습니다. 코틀린 탐색과 리팩터링은 grep보다 **LSP 도구(goToDefinition, findReferences 등)를 우선** 사용합니다(kotlin-lsp 플러그인 활성화됨).
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

### 도메인 불변식과 API 규칙

- **스토리 공개 읽기 게이트**: publicId로 스토리를 읽는 **모든 공개 소비자**(상세, batch, 자식 리소스 GET, 채팅 생성 등)는 `Story.isReadableBy(userId)`(PUBLISHED∧PUBLIC ∨ 소유자)를 반드시 적용합니다. 빠뜨리면 비공개 초안이 유출됩니다. 스토리 관련 엔드포인트를 추가하거나 바꾸면 이 게이트부터 확인합니다.
- **외부 노출 식별자는 public_id(UUID)**: 순차 PK를 API에 노출하지 않습니다(IDOR 방지). 외부 노출 가능성이 있는 새 테이블은 public_id를 함께 설계합니다.
- CORS `allowedMethods`는 GET, POST, PUT, PATCH, DELETE, OPTIONS를 허용합니다(스토리 수정이 PATCH 사용, `SecurityConfig.kt`). 과거 "PATCH 금지, PUT만" 규칙은 폐기됐습니다(2026-08-29 확인). PATCH를 쓴다는 이유로 지적하지 않습니다.
- 컬렉션 전체 교체 PUT은 필드 누락을 400으로 거부해 silent wipe를 방지하고, `sort_order`는 1-based를 사용합니다.
- refresh 토큰은 Redis(휘발성)에만 저장합니다. `refresh_tokens` 테이블은 PG와 ERD에 없는 게 맞습니다.

### 테스트와 마이그레이션 검증

- 테스트 프로파일은 **H2 + `ddl-auto` + `flyway.enabled=false`**입니다. 따라서 **`./gradlew test` 통과는 마이그레이션이나 DB 체크 제약 검증이 아닙니다**. 체크 제약 위반을 red로 만드는 TDD 시나리오도 성립하지 않으니, 리뷰에서 그런 테스트의 부재를 지적하지 않습니다.
- 마이그레이션 검증은 `scripts/gen-db-docs.sh` 경로(실 PostgreSQL에 Flyway 적용)로 하고, 재생성된 `dbdoc/`을 함께 커밋합니다. PR 본문에 이 대체 검증 방법을 명시합니다.
- 테스트 격리는 `build.gradle.kts`의 `tasks.withType<Test>`가 빌드로 강제합니다(프로파일 test 강제 + 실 DB env 차단). 회귀 가드는 `TestDatasourceIsolationGuardTests.kt`이며, 이 장치를 약화시키지 않습니다.
- PostgreSQL `RENAME COLUMN`은 인덱스 카탈로그에 옛 컬럼명을 남기므로, rename된 컬럼을 덮는 UNIQUE와 인덱스는 마이그레이션에서 DROP+ADD로 재생성합니다.
- 모듈 경계 가드 `ModuleBoundaryTests`는 R1(다른 모듈의 리포지토리와 엔티티 직접 참조), R2(모듈 간 순환 간선), G(global→도메인) 위반이 **하나라도 있으면 실패**하고 위반 줄을 출력합니다(KNK-2080에서 셋 다 0이 되어 기준 파일과 갱신 모드를 없앴습니다). 다른 모듈의 값이 필요하면 그 모듈이 결과 타입을 돌려주는 조회 서비스, 잠금 진입점, 명령을 두고 호출합니다. 아래 모듈이 위 모듈을 알아야 하면 아래 모듈이 인터페이스나 이벤트를 정의합니다(BE-061). 쿼리 문자열로 다른 모듈의 엔티티 이름을 새로 참조해 가드를 우회하지 않습니다.

## DB 스키마 문서

- DB 스키마(마이그레이션)를 변경하면 `scripts/gen-db-docs.sh`로 `dbdoc/`(tbls가 실 DB에서 생성하는 ERD 문서)를 재생성해 함께 커밋합니다. 현행 ERD는 `dbdoc/`, 목표 설계는 `docs/schema-roadmap.sql`을 보며, ERDCloud는 유지보수하지 않습니다.
- 아키텍처 문서는 현재와 목표를 분리 관리합니다. 인프라 도면은 레포 `docs/images/cloud-architecture-v1.png`\~`v6.png`(버전별, 원본 drawio는 manyak-terraform `docs/architecture/`. `docs/architecture-v1.*`은 초기 도면)이고, 이상적 목표(To-Be)는 노션 "Manyak 서버 아키텍처"(버전 관리 DB)입니다. 둘을 섞지 않습니다.

## 배포와 릴리스

- **main push만 프로덕션 배포를 트리거**합니다(`.github/workflows/docker-image.yml`의 `deploy-prod`). dev 머지는 프로덕션에 반영되지 않습니다.
  - 운영은 EC2 앱 호스트 한 대(태그 `manyak:host=manyak-prod-host`, KNK-2106)입니다.
  - 빌드 잡이 ECR에 `:<sha7>`를 push합니다. 배포 잡은 직렬화된 구간에서 현재 main HEAD를 배포 대상으로 정하고, 그 이미지가 ECR에 아직 없으면 멈춥니다(그 커밋의 실행이 배포합니다).
  - SSM 문서 `manyak-prod-host-deploy-server`로 호스트 `deploy.sh`를 부릅니다. 새 컨테이너 헬스 → kamal-proxy 전환 → 옛 컨테이너 드레인(240초) 후 정지입니다. 새 컨테이너가 헬스를 못 넘으면 지워지고 옛 server가 계속 서비스합니다. 출력의 `server: deployed <image>` 줄로 배포를 확인합니다.
  - `api.manyak.app/actuator/health` 스모크가 실패하면 같은 문서를 `image=rollback`으로 보냅니다. 배포가 끝난 뒤에만 `:latest`를 그 SHA로 옮깁니다.
  - 되돌리기는 SSM 문서를 `image=rollback`(직전 이미지, 호스트 `/data/state` 기록) 또는 `image=<ECR 이미지>:<sha7>`로 실행하거나, revert 커밋을 릴리스합니다.
- **dev push는 홈서버 dev 스택에 반영**됩니다(KNK-2125, manyak-terraform `homeserver/dev`).
  - 배포 잡(`deploy-dev`)이 최신 SHA 게이트 뒤에서 GHCR `:<sha7>`를 `:dev`로 옮기고 `dev-api.manyak.app` 헬스를 확인합니다. 배포 명령은 보내지 않습니다.
  - 홈서버 updater가 1분마다 `:dev` 다이제스트를 확인해 바뀌었으면 pull하고 컨테이너를 다시 만듭니다. 교체하는 동안 잠깐 멈춥니다(무중단 아님).
  - `dev-api`, `dev-cdn`은 Cloudflare 터널로 들어옵니다. dev EC2, SSM 배포, SQS, dev ECR 경로는 없습니다.
  - 되돌리기는 홈서버 compose에서 server `image`를 불변 태그(`:<sha7>`)로 바꾸고 `docker compose up -d server`를 실행합니다.
- 머지 규칙: 기능→dev = Squash, `release/vX.Y.Z`→main = **Merge Commit**, release→dev = Rebase 역반영, `hotfix/*`→main = **Merge Commit**, hotfix→dev = Rebase(릴리스 역반영과 같음). main과 dev에 직접 push하지 않습니다(관례).
- 릴리스 절차: dev에서 `release/vX.Y.Z` 분기 → PR 제목 `[KNK-xxx] Release: vX.Y.Z 배포` → 머지 후 `vX.Y.Z` git 태그. `build.gradle.kts` version은 올리지 않습니다(이미지 태그는 git sha 기반).
- 긴급 수정: 운영 장애나 보안 문제를 바로 고쳐야 하는데 dev에 아직 운영에 내보낼 수 없는 변경이 있을 때만 `origin/main`에서 `hotfix/KNK-xxx-…`로 분기합니다 → PR 제목 `[KNK-xxx] Hotfix: vX.Y.Z 배포` → main 머지 후 patch 태그 → 곧바로 같은 브랜치를 dev에 반영. 그런 변경이 없으면 위 릴리스 절차로 patch를 냅니다. ([운영 긴급 수정](../knk-harness/docs/operations/DEPLOYMENT.md#7-3-운영-긴급-수정))
- 릴리스 PR 본문과 릴리스 티켓에는 포함 PR 목록과 별도로 **"코드 외 할 일"** 칸을 둡니다. 설정 켜기, `terraform apply`처럼 이미지 배포만으로 끝나지 않는 작업과 다음 릴리스에 묶기로 한 설정 변경을 적고, 없으면 "없음"이라고 적습니다. 이 칸의 항목이 모두 끝나야 릴리스 티켓을 완료합니다.
- 기능 설정을 켜거나 끄는 배포는 배포 확인 때 **실행 중인 server 컨테이너의 해당 환경 변수가 의도한 값인지 대조**합니다(운영 호스트의 `/opt/manyak/config/services/server.env`, 또는 SSM으로 `docker inspect`. 값은 출력하지 않고 키와 비밀 아닌 값만 봅니다). 헬스 200만으로 판정하지 않습니다.
- 와이어 계약(요청과 응답 필드)이 바뀌는 릴리스는 manyak-web, manyak-ai와 **동반 배포**가 필요한지 확인합니다. 반쪽 배포는 런타임 장애로 이어집니다.
- 비밀값이 대화나 PR에 평문 노출되면 값을 재출력하지 말고 노출 사실을 짚고 재발급을 권합니다. Secrets Manager `put-secret-value`는 전체 덮어쓰기이므로 기존 키를 보존해 머지(jq)합니다.

## Terraform/IaC 작업

- 운영 인프라(Terraform/IaC)는 `manyak-terraform` 레포로 분리됐습니다(KNK-296). 이 레포에는 IaC 코드를 두지 않으며, terraform 작업, 제약, apply는 그 레포의 지침을 따릅니다.
- 이 레포에서 알아야 할 최소한: **인프라 정의 변경은 앱 레포 배포 잡으로 반영되지 않고 terraform apply가 필요**합니다.
  - 운영 컨테이너 환경변수, 시크릿 참조, 헬스체크는 manyak-terraform `envs/prod/app-host.tf`의 `app_host_defs`가 정본입니다. terraform이 호스트 설정(S3)으로 내려보내고, 배포 잡은 이미지 태그만 바꿉니다.
  - 설정을 바꾼 apply는 다음 배포 때(또는 SSM 문서 `image=current`로 다시 배포할 때) 반영되므로 **apply는 반드시 해당 코드의 main 릴리스 후에** 합니다.
  - dev 컨테이너 설정은 manyak-terraform `homeserver/dev`의 `compose.yaml`과 홈서버의 `secrets/server.env`(레포에 없음)에 있습니다. terraform apply 대상이 아닙니다.
