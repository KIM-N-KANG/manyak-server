# Manyak Server LLM 하네스

이 프로젝트는 LLM 보조 작업의 기본 지식 소스로 로컬 팀 위키를 사용합니다.

## 위키 소스

- 로컬 위키 경로: `./wiki`
- 기본 진입점: `wiki/index.md`
- 팀 운영 규칙: `wiki/CLAUDE.md`, `wiki/.claude/commands/*`
- 정리된 지식 페이지: `wiki/wiki/**/*.md`
- 원본 및 근거 페이지: `wiki/raw/**`, `wiki/wiki/sources/**`

## 필수 행동 규칙

- 이 저장소에서 작업을 시작할 때 이 파일을 먼저 읽습니다.
- GitHub나 API 접근보다 로컬 위키 파일을 우선합니다.
- 사용자가 온라인 조회를 명시적으로 요청했거나 로컬 위키 내용이 없고 온라인 검증이 필요할 때만 GitHub를 대체 수단으로 사용합니다.
- `./wiki`가 없다면 GitHub API를 반복 호출하지 말고, 사용자에게 아래 명령으로 복제하라고 안내합니다.

  ```bash
  git clone https://github.com/KIM-N-KANG/llm-wiki.git wiki
  ```

- 위키 최신성이 중요하면 사용자에게 아래 명령을 실행하라고 안내합니다.

  ```bash
  git -C wiki pull
  ```

- 위키 내용을 이 저장소로 복사하지 않습니다.
- `wiki/` 아래 파일을 이 저장소 커밋에 포함하지 않습니다.
- `wiki/`는 `manyak-server`와 별도로 관리되는 독립 Git 저장소로 취급합니다.

## Jira 작업 흐름

- 작업은 보통 Jira 이슈와 연결합니다.
- 사용자가 `KNK-92` 같은 이슈 키를 언급하거나 "Jira 티켓 92"라고 말하면, 계획이나 편집 전에 Jira 이슈를 먼저 조회합니다.
- 이슈 제목, 설명, 댓글, 라벨, 연결 이슈, 하위 작업, 첨부 파일을 작업 맥락으로 사용합니다.
- 이슈가 모호하거나 완료 기준이 없다면, 편집 전에 부족한 점을 말하고 좁은 구현 범위를 제안합니다.
- 팀 위키의 브랜치 이름 규칙을 사용합니다: `{tag}/KNK-{issue-number}-{branch-title}`.
- 팀 위키의 커밋 메시지 규칙을 사용합니다: `[KNK-{issue-number}] {Tag}: {commit title}`.
- PR을 만들 때는 먼저 `.github/pull_request_template.md`를 읽고, 해당 템플릿 형식으로 PR 본문을 작성합니다.
- 사용자가 명시적으로 요청하지 않는 한 Jira 상태 변경, 담당자 지정, Jira 댓글 작성을 하지 않습니다.
- 구현 후 변경 파일, 검증 결과, Jira 업데이트 문구 제안을 요약합니다.

## Kotlin Spring 작업

- 이 백엔드는 Kotlin, Spring Boot, Gradle Kotlin DSL, Java 21, JPA, Flyway, Security, PostgreSQL을 사용합니다.
- 편집 전에 기존 패키지 구조와 빌드 설정을 읽습니다.
- 보일러플레이트 변경은 작고 단순하게 유지합니다. 프로젝트에 명확한 이유가 없다면 Spring Boot 기본값을 우선합니다.
- 비즈니스 API prefix는 `/api/v1`을 사용합니다.
- 운영 상태 확인에는 Spring Boot Actuator를 사용합니다. 구체적인 제품 또는 인프라 요구사항이 없다면 별도 health endpoint를 추가하지 않습니다.
- 외부에서 설정 가능한 값은 Spring 설정 또는 환경변수로 둡니다. 실제 비밀값은 커밋하지 않습니다.
- JPA, Flyway, Security, datasource, API 동작을 변경할 때는 완료 보고 전에 관련 Gradle 검증을 실행합니다.
- 로컬 환경 제한으로 검증을 실행할 수 없다면 정확한 차단 사유와 실행해야 할 명령을 설명합니다.

## Obsidian

- `wiki/` 디렉터리는 Obsidian vault로 바로 열 수 있습니다.
- 위키 편집은 이 서버 저장소가 아니라 `wiki/` 내부에서 커밋하고 push해야 합니다.
