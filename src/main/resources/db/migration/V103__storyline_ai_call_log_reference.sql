-- HTTP 추적 ID와 제작 요청 UUID는 다른 식별자이므로 세션에 생성 호출의 PK를 직접 보존한다.
-- 기존 세션과 로그는 백필하지 않는다. 관측 로그가 정리돼도 제작 세션은 유지한다.
ALTER TABLE story_creation_sessions
    ADD COLUMN storyline_ai_call_log_id bigint
    REFERENCES ai_call_logs(id) ON DELETE SET NULL;
COMMENT ON COLUMN story_creation_sessions.storyline_ai_call_log_id IS
    '이 세션을 생성한 스토리라인 AI 호출. 완성 시 해당 로그의 story_id를 연결하며 기존 세션은 NULL';
