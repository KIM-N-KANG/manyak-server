-- AiCallFeature 예약값 suggestion_generation을 용어집(KNK-360) 기준 choice_generation으로 정렬한다.
-- suggestion_generation은 적재 지점이 없는 미사용 예약값이라 기존 행 데이터 마이그레이션은 필요 없다.
ALTER TABLE ai_call_logs DROP CONSTRAINT ck_ai_call_logs_feature;
ALTER TABLE ai_call_logs ADD CONSTRAINT ck_ai_call_logs_feature
    CHECK (feature IN ('storyline_generation', 'story_completion', 'chat_response', 'choice_generation'));
