-- KNK-1069: 컴파일이 생성한 표지 썸네일의 절대 URL을 스토리에 저장한다.
-- 기존 thumbnail_image_key(프리셋 카탈로그 키)에는 image_presets FK가 걸려 있어 생성 자산 URL을 넣을 수 없다.
-- 그래서 인물 이미지(story_characters.image_url) 선례대로 URL 컬럼을 따로 둔다.
-- 프리셋 자동 연결은 계속 돌아간다 — 구버전 AI·생성 실패·일반 제작·기존 스토리가 전부 프리셋 경로에
-- 남아야 하고, 이 컬럼이 비면 노출이 자동으로 프리셋으로 떨어진다(2단 폴백은 ImageUrlResolver가 소유).
-- 백필은 하지 않는다: 기존 스토리에는 생성 썸네일 자체가 없다.
ALTER TABLE stories ADD COLUMN thumbnail_image_url TEXT;
