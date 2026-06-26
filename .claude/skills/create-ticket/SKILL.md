---
name: create-ticket
description: KNK 프로젝트에 Jira 티켓(작업/버그/스토리/에픽)을 새로 만들 때 사용합니다. "티켓 만들어줘", "작업 티켓 생성", "현재 스프린트에 티켓 추가해줘", "서브태스크 만들어줘", "이거 지라에 티켓으로 올려줘"처럼 이슈 생성을 요청할 때 사용합니다. 제목은 간결한 명사구로, 구체 내용은 description(마크다운)/서브태스크로 나누는 팀 규칙과, manyak Jira의 비자명한 좌표(cloudId·이슈타입·현재 스프린트 필드·담당자 기본값)를 인코딩해 둡니다. 기존 티켓으로 브랜치를 만드는 일은 `create-branch`, 머지 후 완료 처리는 `complete-ticket`을 쓰고, 이 스킬은 티켓 생성 단계입니다.
---

# Jira 티켓 생성

## 목적

KNK 보드에 일관된 형식의 티켓을 만듭니다. 핵심은 **제목을 간결하게 유지하고(짧은 명사구), 배경·변경·검증 같은 구체 내용은 description이나 서브태스크로 분리**하는 것입니다. (메모리: [[jira-ticket-concise-title]])

## manyak Jira 좌표 (고정값)

- **cloudId**: `7172bb95-f290-4a32-84b8-6c23f20d9cc0` (확신이 없으면 `getAccessibleAtlassianResources`로 확인)
- **projectKey**: `KNK`
- **이슈 유형** (`issueTypeName`):
  - `작업` (Task) — 기본값. 대부분의 개별 업무
  - `버그` (Bug) — 결함·오류
  - `스토리` (Story) — 사용자 스토리
  - `에픽` (Epic) — 관련 작업 묶음
  - `Subtask` — 상위 이슈에 속하는 하위 작업 (생성 시 `parent`에 상위 키 지정)
- **현재 스프린트 필드**: `customfield_10020` (스프린트 id를 값으로)

## 제목 규칙

- 짧은 한국어 명사구로 작성합니다. 보드 관례: `웹 v0.1.1 배포`, `채팅 추천 입력 개수 강제`, `운영 배포에 manyak-ai 컨테이너 배선`.
- 변경 항목을 제목에 나열하지 않습니다. 여러 항목·배경·절차는 description으로 내립니다.
- 태그 접두사(`[Feat]` 등)는 붙이지 않습니다. 제목은 내용 자체만.

## 작업 흐름

1. **이슈 유형을 정합니다.** 사용자가 명시하지 않으면 `작업`을 기본으로 하되, 결함이면 `버그`, 사용자 스토리면 `스토리`로 판단합니다. 상위 이슈가 명확한 하위 작업이면 `Subtask` + `parent`.
2. **간결한 제목**을 만듭니다(위 규칙).
3. **description을 마크다운으로 작성**합니다. 내용이 단순하면 한두 줄로 충분하고, 복잡하면 아래 섹션 골격을 씁니다(불필요한 섹션은 생략).
   - `## 배경` — 왜 하는가
   - `## 변경` 또는 `## 작업` — 무엇을 하는가(불릿/체크리스트)
   - `## 주의` — 리스크·의존성·롤백 등(있을 때만)
   - `## 검증` — 완료를 어떻게 확인하나(있을 때만)
   - `contentFormat: "markdown"`으로 넘깁니다.
4. **담당자**를 정합니다. 기본값은 요청자(사용자) 본인입니다. 다른 사람을 지정하면 `lookupJiraAccountId`로 accountId를 찾습니다. 사용자 본인 accountId는 `atlassianUserInfo`로 확인합니다.
5. **현재 스프린트에 배치**합니다(사용자가 백로그를 원하지 않는 한 기본은 현재 스프린트). 활성 스프린트 id는 고정이 아니므로 **매번 동적으로 조회**합니다.
   - `searchJiraIssuesUsingJql`로 `project = KNK AND sprint in openSprints()` 한 건 조회 → 결과의 `customfield_10020[].id`(state=active)에서 현재 스프린트 id를 읽습니다.
   - `additional_fields: { "customfield_10020": <sprintId> }`로 설정합니다.
6. **티켓을 생성**합니다(`createJiraIssue`). 상태는 기본 `해야 할 일`로 둡니다. 사용자가 "바로 착수"라고 하면 `transition`으로 진행 중(보통 id `21`)으로 둘 수 있지만, 보통 생성과 착수는 분리합니다(착수·브랜치는 `create-branch`).
7. **서브태스크가 필요하면** 상위 티켓 생성 후, 각 단위를 `issueTypeName: "Subtask"` + `parent: "<상위키>"`로 만듭니다. 단, 이미 끝난 소규모 작업이나 단일 변경은 서브태스크 없이 description 체크리스트로 충분합니다 — 불필요한 서브태스크를 남발하지 않습니다.

## 도구 사용법

현재 스프린트 id 조회:

```
searchJiraIssuesUsingJql(
  cloudId, jql="project = KNK AND sprint in openSprints()",
  fields=["customfield_10020"], maxResults=1)
# → customfield_10020 배열에서 state=="active"인 항목의 id
```

티켓 생성(작업):

```
createJiraIssue(
  cloudId, projectKey="KNK", issueTypeName="작업",
  summary="<간결한 제목>",
  assignee_account_id="<요청자 accountId>",
  contentFormat="markdown", description="<마크다운 본문>",
  additional_fields={"customfield_10020": <현재 스프린트 id>})
```

서브태스크 생성:

```
createJiraIssue(cloudId, projectKey="KNK", issueTypeName="Subtask",
  parent="KNK-123", summary="...", ...)
```

## 금지 사항

- 제목에 변경 목록·절차·배경을 욱여넣지 않습니다(간결한 명사구 유지).
- 스프린트 id를 하드코딩하지 않습니다. 매번 `openSprints()`로 현재 id를 조회합니다.
- 이슈 유형·담당자를 추측으로 확정하지 않습니다. 모호하면 사용자에게 확인합니다.
- 끝난 소규모 작업에 불필요한 서브태스크를 만들지 않습니다.
- description에 실제 secret, 프롬프트 전문, 채팅 원문을 넣지 않습니다.

## 완료 보고

- 생성한 티켓 키와 URL, 제목을 보고합니다.
- 이슈 유형, 담당자, 배치한 스프린트를 알립니다.
- 서브태스크를 만들었다면 각 키와 제목을 함께 알립니다.
- description에 어떤 섹션을 넣었는지 한 줄로 요약합니다.
