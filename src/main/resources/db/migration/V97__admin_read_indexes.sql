-- 페이지 기본 정렬의 UUID 동률 키와 이메일 정확 일치 조회를 지원한다.
CREATE INDEX idx_users_admin_created_public ON users (created_at DESC NULLS LAST, public_id ASC);
CREATE INDEX idx_credit_orders_admin_completed_public ON credit_orders (completed_at DESC NULLS LAST, public_id ASC)
    WHERE completed_at IS NOT NULL;
CREATE INDEX idx_admin_audit_logs_created_public ON admin_audit_logs (created_at DESC NULLS LAST, public_id ASC);
CREATE INDEX idx_social_accounts_active_email_user ON social_accounts (email, user_id)
    WHERE deleted_at IS NULL AND email IS NOT NULL;
