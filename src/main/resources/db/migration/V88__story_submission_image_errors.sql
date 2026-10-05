ALTER TABLE story_submissions
    ADD COLUMN image_errors JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(image_errors) = 'array');

COMMENT ON COLUMN story_submissions.image_errors IS 'AI 이미지 실행 오류 목록(path, errorCode). 원본 입력 경로를 보존하고 조회 시 현재 폼으로 재매핑';
