-- KNK-1059: 채팅 시작 시점의 스토리 제목·썸네일을 story_chats에 박아둔다.
-- 소유자가 공개했던 스토리를 비공개·초안으로 되돌리거나 지운 뒤 제목·썸네일을 바꾸면, 그 값이 이미 채팅을
-- 시작한 다른 사용자의 서재·이용내역으로 계속 흘러갔다(PR #216 Codex P1). 읽기가 허용될 때만 현재 값을 쓰고
-- 그 외에는 이 스냅샷에서 멈춘다.
-- 원본 컬럼과 타입을 맞춘다: stories.title varchar(100), stories.thumbnail_image_key varchar(64).
-- 둘 다 nullable이다 — 썸네일은 원래 nullable이고, 백필 후에도 스토리가 사라진 고아 행이 남을 수 있다.
ALTER TABLE story_chats ADD COLUMN story_title_snapshot VARCHAR(100);
ALTER TABLE story_chats ADD COLUMN story_thumbnail_key_snapshot VARCHAR(64);

-- 기존 행 백필. 과거 시점 값은 복원할 수 없으니 현재 값이 최선치다. 게스트 소유(user_id IS NULL) 행도 채운다.
UPDATE story_chats sc
SET story_title_snapshot = s.title,
    story_thumbnail_key_snapshot = s.thumbnail_image_key
FROM stories s
WHERE sc.story_id = s.id;

-- FK는 걸지 않는다: 스냅샷은 스토리가 바뀌거나 사라져도 남아야 하는 값이라 참조 무결성 대상이 아니다.
