ALTER TABLE story_chats ADD COLUMN summary_through_turn integer NOT NULL DEFAULT 0
    CONSTRAINT ck_story_chats_summary_through_turn CHECK (summary_through_turn >= 0);

ALTER TABLE ai_call_logs DROP CONSTRAINT ck_ai_call_logs_feature;
ALTER TABLE ai_call_logs ADD CONSTRAINT ck_ai_call_logs_feature
    CHECK (feature IN ('storyline_generation', 'story_completion', 'chat_response', 'choice_generation', 'chat_image', 'chat_compact'));
