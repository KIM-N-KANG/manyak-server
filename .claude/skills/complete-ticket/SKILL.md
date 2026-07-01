---
name: complete-ticket
description: PR을 머지한 뒤 마무리 작업(로컬 브랜치 정리 + Jira 티켓 완료 처리 + 작업 시간 worklog 기록)을 한 번에 수행할 때 사용합니다. "머지했어 브랜치 정리해줘", "로컬 브랜치들 정리해줘", "지라 티켓 완료 처리하고 작업 시간 1시간 기록해줘", "티켓 완료 처리랑 시간 등록, 로컬 브랜치 정리 해줘"처럼 머지 후 뒷정리를 요청할 때 사용합니다. 세 작업 중 일부만 요청해도(예: 브랜치 정리만, 또는 worklog만) 이 스킬의 해당 단계를 따릅니다. squash 머지된 브랜치는 `git branch -d`로 감지되지 않아 머지 확인 후 `-D`로 지워야 하고, Jira 완료 트랜지션/worklog는 Atlassian MCP로 처리하는 비자명한 규칙이 있으니 직접 추측하지 말고 이 스킬을 따르세요.
---

# 머지 후 티켓 마무리

## 목적

PR이 `dev`(또는 `main`)에 머지된 뒤 남는 뒷정리를 안전하게 끝냅니다. 세 가지로 구성됩니다.

1. **로컬 브랜치 정리** — 머지된 작업 브랜치를 지우고 원격 추적 참조를 prune
2. **Jira 티켓 완료 처리** — 해당 이슈를 완료(Done) 상태로 트랜지션
3. **작업 시간 기록** — worklog로 소요 시간을 적재

핵심은 "빨리 지우는 것"이 아니라, **실제로 머지된 것만 지우고**(되돌릴 수 없는 삭제 사고 방지), **올바른 티켓에 올바른 트랜지션/시간을 남기는 것**입니다.

## 사전 확인

- 사용자가 "머지했다"고 했더라도 직접 머지 상태를 확인합니다. PR이 아직 열려 있으면 삭제하지 말고 알립니다.
- 대상 Jira 키는 브랜치 이름 `{tag}/KNK-{번호}-{제목}`에서 추출합니다. 찾을 수 없으면 추측하지 말고 사용자에게 키를 확인합니다.
- 사용자가 일부만 요청했으면(예: 브랜치 정리만) 그 단계만 수행합니다. 단, 세 작업을 함께 요청하는 경우가 일반적입니다.

## 1. 로컬 브랜치 정리

기본 브랜치는 `dev`입니다. 순서:

1. 현재 머지된 PR과 브랜치를 확인합니다.
   - `git branch --show-current`
   - `gh pr view <PR번호 또는 브랜치> --json state,headRefName,mergedAt` — `state`가 `MERGED`인지 확인
2. 기본 브랜치로 이동해 최신화합니다.
   - `git switch dev` (작업 브랜치에 있을 때)
   - `git fetch origin --prune`
   - `git pull --ff-only origin dev`
3. 머지된 작업 브랜치를 삭제합니다.
   - **중요**: 이 레포는 PR을 **squash 머지**하므로 `git branch -d`(머지 여부를 커밋 조상으로 판단)는 "not fully merged"로 거부합니다. 그래서 `gh`로 `MERGED`를 먼저 확인한 뒤 `git branch -D <branch>`로 지웁니다.
   - 워크트리에서 만든 임시 브랜치(`worktree-agent-*` 등)나 더 이상 필요 없는 로컬 브랜치도 함께 정리합니다.
4. 원격 추적 참조를 정리합니다.
   - `git remote prune origin` (또는 위 `git fetch origin --prune`로 갈음)
5. 정리 결과를 확인합니다.
   - `git branch -vv` — 남은 로컬 브랜치와 `[gone]` 표시 확인

## 2. Jira 티켓 완료 처리

Atlassian MCP를 사용합니다. manyak Jira의 cloudId는 `7172bb95-f290-4a32-84b8-6c23f20d9cc0`입니다. 확신이 없으면 `getAccessibleAtlassianResources`로 확인합니다.

1. 사용 가능한 트랜지션을 조회합니다.
   - `getTransitionsForJiraIssue` (issueIdOrKey = `KNK-{번호}`)
2. 이름이 "완료" 또는 "Done"인 트랜지션을 골라 실행합니다.
   - `transitionJiraIssue` — `transition.id`에 위에서 찾은 완료 트랜지션 id를 넣습니다.
   - 이 보드에서는 완료 트랜지션 id가 보통 `31`이지만, **id를 가정하지 말고 항상 `getTransitionsForJiraIssue` 결과의 이름으로 매칭**합니다(보드 설정이 바뀔 수 있음).
3. 이미 완료 상태면 중복 트랜지션을 시도하지 않고 그대로 보고합니다.

## 3. 작업 시간 기록 (worklog)

`addWorklogToJiraIssue`로 적재합니다.

- `issueIdOrKey`: `KNK-{번호}`
- `timeSpent`: 사용자가 말한 시간. **명시가 없으면 기본 `1h`** (이 팀의 기본 단위). "30분"→`30m`, "1시간 30분"→`1h 30m` 형식.
- `commentBody`: 한국어 한 줄 작업 요약. PR 제목/커밋 내용을 근거로 무엇을 했는지 간결하게 적습니다. 추측이나 과장 없이 실제 변경만 적습니다.
- `started`: 사용자가 시작 시각을 주면 ISO8601(+0900)로 넣고, 없으면 생략합니다.

worklog 코멘트 예: `"표준 4xx 예외 핸들러 보강(406/415/405, 405 Allow 헤더) + http 검증 파일 추가"`

## 명령 사용법

머지 상태 확인:

```bash
gh pr view <PR번호> --json state,headRefName,mergedAt
```

브랜치 정리(머지 확인 후):

```bash
git switch dev
git fetch origin --prune
git pull --ff-only origin dev
git branch -D <merged-branch> [<extra-branch> ...]
git branch -vv
```

## 금지 사항

- PR이 `MERGED`임을 확인하지 않고 `git branch -D`로 브랜치를 지우지 않습니다.
- `dev`, `main`, `release/*` 같은 보호 브랜치를 로컬에서 지우지 않습니다.
- 브랜치에서 Jira 키를 찾지 못한 채 임의의 `KNK-*`를 완료 처리하거나 worklog를 남기지 않습니다.
- 트랜지션 id를 이름 확인 없이 하드코딩으로 가정하지 않습니다.
- 실제로 한 작업과 다른 내용을 worklog 코멘트에 적지 않습니다.

## 완료 보고

- 삭제한 로컬 브랜치 목록과 남은 브랜치 상태를 보고합니다.
- 완료 처리한 Jira 키와 트랜지션 결과(이전 상태→완료)를 알립니다.
- 기록한 worklog의 `timeSpent`와 코멘트 요약을 알립니다.
- 일부만 수행했거나(요청 범위) 건너뛴 단계가 있으면 그 이유를 짧게 알립니다.
