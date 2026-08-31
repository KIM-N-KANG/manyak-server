-- KNK-1059: 채팅 시작 시점의 스토리 제목·썸네일을 story_chats에 박아둔다.
-- 소유자가 공개했던 스토리를 비공개·초안으로 되돌리거나 지운 뒤 제목·썸네일을 바꾸면, 그 값이 이미 채팅을
-- 시작한 다른 사용자의 서재·이용내역으로 계속 흘러갔다(PR #216 Codex P1). 읽기가 허용될 때만 현재 값을 쓰고
-- 그 외에는 이 스냅샷에서 멈춘다.
-- 원본 컬럼과 타입을 맞춘다: stories.title varchar(100), stories.thumbnail_image_key varchar(64).
-- 둘 다 nullable이다 — 썸네일은 원래 nullable이고, 백필 후에도 스토리가 사라진 고아 행이 남을 수 있다.
ALTER TABLE story_chats ADD COLUMN story_title_snapshot VARCHAR(100);
ALTER TABLE story_chats ADD COLUMN story_thumbnail_key_snapshot VARCHAR(64);
-- 프롤로그(스토리 도입부 본문)도 같은 이유로 박는다. 오히려 유출 폭이 크다 — 공유 열람은 무인증 경로라
-- 비공개로 되돌린 스토리의 도입부가 링크만 가진 누구에게나 보였다.
-- 원본과 타입을 맞춘다: story_start_settings.prologue TEXT(nullable).
ALTER TABLE story_chats ADD COLUMN story_prologue_snapshot TEXT;

-- 기존 행 백필. 과거 시점 값은 복원할 수 없으니 현재 값이 최선치다. 게스트 소유(user_id IS NULL) 행도 채운다.
UPDATE story_chats sc
SET story_title_snapshot = s.title,
    story_thumbnail_key_snapshot = s.thumbnail_image_key
FROM stories s
WHERE sc.story_id = s.id;

-- 프롤로그는 스토리가 아니라 그 채팅이 시작한 시작 설정에 달려 있어 start_setting_id를 타고 조인한다.
-- start_setting_id가 NULL인 기존 행(시작 설정 없이 만든 채팅)은 채울 근거가 없어 NULL로 남는다 —
-- 그런 채팅은 원래 프롤로그가 빈 문자열이라 노출 값이 달라지지 않는다.
UPDATE story_chats sc
SET story_prologue_snapshot = ss.prologue
FROM story_start_settings ss
WHERE sc.start_setting_id = ss.id;

-- FK는 걸지 않는다: 스냅샷은 스토리가 바뀌거나 사라져도 남아야 하는 값이라 참조 무결성 대상이 아니다.
