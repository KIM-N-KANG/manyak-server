-- 실시간 이미지 관측을 추가하며 기존 기능 값 네 개를 유지한다.
ALTER TABLE ai_call_logs DROP CONSTRAINT ck_ai_call_logs_feature;
ALTER TABLE ai_call_logs ADD CONSTRAINT ck_ai_call_logs_feature
    CHECK (feature IN ('storyline_generation', 'story_completion', 'chat_response', 'choice_generation', 'chat_image'));
