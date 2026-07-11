-- 회원 서재(내 콘텐츠 목록, KNK-447) 조회를 뒷받침하는 인덱스.
--
-- 조회 술어/정렬:
--   내 스토리:  WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC  (상한 100)
--   내 채팅:    WHERE user_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC, id DESC  (상한 100)
--
-- stories·story_chats 는 게스트/회원이 공유하는 큰 테이블이라, 소유자별 조회가 전역 행 수에 비례해 느려지지 않도록
-- 술어에 맞는 부분 인덱스(deleted_at IS NULL)를 둔다. 정렬 키를 인덱스에 포함해 정렬까지 인덱스 스캔으로 처리한다.

CREATE INDEX idx_stories_user_created
    ON stories (user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_story_chats_user_updated
    ON story_chats (user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
