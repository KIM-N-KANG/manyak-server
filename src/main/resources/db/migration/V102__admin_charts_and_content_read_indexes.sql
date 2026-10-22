-- 관리자 기간 집계와 상세의 메타데이터 조회. 메시지 원문은 인덱스에도 포함하지 않는다.
CREATE INDEX idx_story_messages_admin_role_created_chat ON story_messages (role, created_at, chat_id);
CREATE INDEX idx_story_messages_admin_chat_role_created ON story_messages (chat_id, role, created_at);
CREATE INDEX idx_story_chats_admin_created_public ON story_chats (created_at DESC, public_id ASC);
CREATE INDEX idx_story_chats_admin_story_created ON story_chats (story_id, created_at DESC, public_id ASC)
    INCLUDE (current_turn) WHERE deleted_at IS NULL;
CREATE INDEX idx_stories_admin_created_public ON stories (created_at DESC, public_id ASC);
CREATE INDEX idx_stories_admin_owner_created ON stories (user_id, created_at DESC);
CREATE INDEX idx_creation_sessions_admin_created ON story_creation_sessions (created_at, user_id);
CREATE INDEX idx_credit_transactions_admin_created ON credit_transactions (created_at, user_id) INCLUDE (amount, reason);
CREATE INDEX idx_credit_transactions_admin_chat_ref ON credit_transactions (ref_id, ref_type)
    INCLUDE (amount) WHERE amount < 0 AND reason IN ('CHAT_TURN', 'CHAT_IMAGE');
CREATE INDEX idx_credit_orders_admin_user_completed ON credit_orders (user_id, completed_at) INCLUDE (status, price_krw);
CREATE INDEX idx_ai_call_logs_admin_chat_created ON ai_call_logs (chat_id, created_at) INCLUDE (feature, status, latency_ms);
CREATE INDEX idx_ai_call_logs_admin_story_created ON ai_call_logs (story_id, created_at) INCLUDE (feature, status, latency_ms);
CREATE INDEX idx_social_accounts_admin_lower_email ON social_accounts (lower(email), user_id)
    WHERE deleted_at IS NULL AND email IS NOT NULL;
