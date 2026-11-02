-- 스위치 배포 전 비공개는 서버 기본값이므로 공개하고, 배포 이후 비공개는 사용자 선택이므로 유지한다.
UPDATE stories
SET visibility = 'PUBLIC'
WHERE user_id IS NULL
  AND visibility = 'PRIVATE'
  AND status = 'PUBLISHED'
  AND deleted_at IS NULL
  AND taken_down_at IS NULL
  AND created_at < TIMESTAMPTZ '2026-10-28 16:05:30+00';
