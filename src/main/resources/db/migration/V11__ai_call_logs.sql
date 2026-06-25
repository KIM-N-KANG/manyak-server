-- AI 호출 단위 이력(품질·비용·실패)을 적재해 서버 로그·저장 데이터와 ai_call_log_id로 연결한다.
-- 프롬프트·원문은 저장하지 않는다(§6·§8). 식별자·메타와 상태(STARTED→SUCCEEDED/FAILED)만 기록한다.
-- provider/model/prompt_template_version/토큰 수는 AI 응답 계약이 확장되면 채우므로 지금은 NULL 허용한다.
CREATE TABLE ai_call_logs (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    caller_service VARCHAR(50) NOT NULL,
    feature VARCHAR(40) NOT NULL,
    anonymous_id_hash VARCHAR(64),
    session_id VARCHAR(128),
    story_id BIGINT,
    chat_id UUID,
    turn_index INT,
    provider VARCHAR(40),
    model VARCHAR(100),
    prompt_template_version VARCHAR(40),
    status VARCHAR(16) NOT NULL,
    latency_ms BIGINT,
    input_token_count INT,
    output_token_count INT,
    retry_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    sentry_event_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,

    CONSTRAINT ck_ai_call_logs_feature
        CHECK (feature IN ('storyline_generation', 'story_completion', 'chat_response', 'suggestion_generation')),
    CONSTRAINT ck_ai_call_logs_status
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_ai_call_logs_retry_count CHECK (retry_count >= 0),
    CONSTRAINT ck_ai_call_logs_turn_index CHECK (turn_index IS NULL OR turn_index >= 0),
    CONSTRAINT ck_ai_call_logs_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

-- request_id로 서버 로그·AI 호출 이력을 상관관계 조회한다.
CREATE INDEX idx_ai_call_logs_request_id ON ai_call_logs(request_id);
-- 최근 호출·실패 추세를 시계열로 조회한다.
CREATE INDEX idx_ai_call_logs_created_at ON ai_call_logs(created_at DESC);
-- 스토리별 호출 분석.
CREATE INDEX idx_ai_call_logs_story ON ai_call_logs(story_id);
