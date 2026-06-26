---
name: request-codex-review
description: 이미 올라간 PR을 ready로 전환하고 `@codex review` 코멘트로 Codex 리뷰를 호출한 뒤, 리뷰가 달릴 때까지 기다렸다가 지적을 판단·반영하는 리뷰 루프를 수행할 때 사용합니다. "ready pr 올리고 @codex review 코멘트 달아서 리뷰 기다려줘", "코덱스가 리뷰 달건데 계속 확인하다가 달리면 반영할지 판단해봐", "@codex review 코멘트로 호출", "리뷰 반영하고 재리뷰 받아줘"처럼 요청할 때 사용합니다. Codex는 **draft PR을 무시**하므로 ready 전환이 선행돼야 하고, 호출은 PR 코멘트로만 트리거되며, 리뷰 도착은 폴링으로 감지해야 하는 비자명한 규칙이 있으니 직접 추측하지 말고 이 스킬을 따르세요. 새 PR을 만드는 일은 `create-pr`로, 이 스킬은 그다음 단계입니다.
---

# Codex 리뷰 요청·반영 루프

## 목적

`create-pr`로 만든 PR에 Codex 리뷰를 받고, 지적을 판단해 반영하고, 필요하면 재리뷰까지 받는 한 사이클을 끝냅니다. 핵심은 "코멘트만 남기는 것"이 아니라, **Codex가 실제로 리뷰를 시작하게 만들고(draft 함정 회피), 리뷰가 도착할 때까지 기다렸다가, 지적의 경중을 판단해 반영하는 것**입니다.

## 비자명한 규칙 (반드시 지킴)

- **Codex는 draft PR을 리뷰하지 않습니다.** 반드시 ready로 먼저 전환합니다. (메모리: codex-review-needs-ready-pr)
- 리뷰 호출은 PR 본문이 아니라 **PR 코멘트 `@codex review`** 로만 트리거됩니다.
- 리뷰는 즉시 오지 않습니다. **폴링으로 도착을 감지**합니다(보통 수 분~20분).
- Codex 봇 계정은 `chatgpt-codex-connector[bot]`입니다.

## 작업 흐름

### 1. PR을 ready로 전환

```bash
gh pr view <PR번호> --json number,state,isDraft,url
gh pr ready <PR번호>     # isDraft=true일 때만
```

- 이미 ready면 전환을 건너뜁니다.
- PR이 없으면 이 스킬을 멈추고 `create-pr`로 먼저 PR을 만들도록 안내합니다.

### 2. Codex 리뷰 호출

```bash
gh pr comment <PR번호> --body "@codex review"
```

- 호출한 코멘트 id를 기록해 두면 재리뷰 시 👍 리액션 감지에 씁니다.

### 3. 첫 리뷰 도착까지 폴링 (최대 ~20분)

`reviews`가 0에서 늘어나면 리뷰가 도착한 것입니다.

```bash
for i in $(seq 1 40); do
  R=$(gh pr view <PR번호> --json reviews --jq '.reviews | length' 2>/dev/null || echo 0)
  if [ "${R:-0}" -gt 0 ]; then echo "CODEX_REVIEW_DETECTED reviews=$R"; exit 0; fi
  echo "poll $i/40: reviews=$R — 30s 대기"
  sleep 30
done
echo "TIMEOUT_20MIN_NO_REVIEW"; exit 0
```

- timeout이면 무한정 기다리지 말고 현재 상태를 사용자에게 보고하고 어떻게 할지 묻습니다.

### 4. 리뷰 내용 확인·판단

```bash
gh pr view <PR번호> --json reviews --jq '.reviews[] | {author: .author.login, state, body}'
# 인라인 코멘트 본문
REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
gh api "repos/$REPO/pulls/<PR번호>/comments" --jq '.[] | select(.user.login=="chatgpt-codex-connector[bot]") | {path, line, body}'
```

- 지적을 **메이저(반영 필요)** 와 **마이너/제안(보류 가능)** 으로 분류합니다.
- 메이저 이슈가 없으면 사용자에게 "메이저 없음 — 머지 가능"으로 보고합니다.
- 반영 여부가 애매한 지적은 임의로 고치지 말고 사용자 판단을 받습니다.

### 5. 반영 후 재리뷰 (지적을 고친 경우)

수정·커밋·push 후 다시 `@codex review` 코멘트를 답니다. 재리뷰는 **리뷰 수 증가** 또는 **이전 호출 코멘트의 👍 리액션**(=추가 지적 없음)으로 감지합니다.

```bash
REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
BOT="chatgpt-codex-connector[bot]"; PR=<PR번호>; CMTID=<재호출 코멘트 id>
PREV=<직전 codex 리뷰 수>
for i in $(seq 1 40); do
  cv=$(gh api "repos/$REPO/pulls/$PR/reviews" --jq "[.[]|select(.user.login==\"$BOT\")]|length" 2>/dev/null || echo "$PREV")
  thumb=$(gh api "repos/$REPO/issues/comments/$CMTID/reactions" --jq "[.[]|select(.content==\"+1\")]|length" 2>/dev/null || echo 0)
  if [ "${cv:-0}" -gt "$PREV" ]; then echo "NEW_REVIEW codex_reviews=$cv"; exit 0; fi
  if [ "${thumb:-0}" -ge 1 ]; then echo "APPROVED_THUMB (👍 — 추가 지적 없음)"; exit 0; fi
  echo "poll $i/40: reviews=$cv thumb=$thumb — 30s 대기"
  sleep 30
done
echo "TIMEOUT_NO_RERESPONSE"; exit 0
```

- 👍 리액션은 "재리뷰 결과 추가 지적 없음"을 뜻합니다. 새 리뷰가 달리면 4단계로 돌아가 다시 판단합니다.

## 금지 사항

- draft 상태로 `@codex review`를 호출하지 않습니다(무시됨). 먼저 ready로 전환합니다.
- 리뷰가 도착하지 않았는데 "리뷰 통과"로 단정하지 않습니다. 반드시 폴링으로 확인합니다.
- 메이저 지적을 사용자 확인 없이 임의 판단으로 무시하지 않습니다.
- 반영 여부가 애매한 제안을 사용자 확인 없이 코드에 반영하지 않습니다.
- 사용자 지시 없이 머지하지 않습니다. 머지 후 마무리는 `complete-ticket`을 사용합니다.

## 완료 보고

- ready 전환 여부와 PR URL을 보고합니다.
- Codex 리뷰 도착 여부와 메이저/마이너 지적 요약을 알립니다.
- 반영한 항목과 보류한 항목을 구분해 알립니다.
- 재리뷰 결과(새 리뷰 vs 👍)를 알리고, 머지 가능 여부에 대한 판단 근거를 남깁니다.
- timeout 등으로 확인하지 못한 부분이 있으면 그 상태를 그대로 알립니다.
