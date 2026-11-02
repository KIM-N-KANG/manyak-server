-- 기존 게스트 완성본을 공개한다. 삭제와 공개 중단된 스토리는 유지한다.
UPDATE stories
SET visibility = 'PUBLIC'
WHERE user_id IS NULL
  AND visibility = 'PRIVATE'
  AND status = 'PUBLISHED'
  AND deleted_at IS NULL
  AND taken_down_at IS NULL;
