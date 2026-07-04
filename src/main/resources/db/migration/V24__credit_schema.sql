-- 크레딧(회원 전용 재화) 데이터 모델: 지갑(credit_wallets)과 불변 원장(credit_transactions).
-- 스펙 §4-3-7 "원장과 동시성":
--   - 모든 증감은 credit_transactions에 append-only 행으로 기록한다(상태 컬럼 대신 보상/환불 행 방식).
--   - credit_wallets.balance는 원장 합계(SUM(amount))의 캐시다. 차감은 지갑 행 비관적 락으로 직렬화한다.
--   - 보상 적립은 결정적 멱등 키(signup:{userId} 등)로 중복을 차단한다. 키는 원장 유니크 제약이다.

CREATE TABLE credit_wallets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 원장 합계의 캐시. 잔액은 음수가 될 수 없다(차감은 잔액 확인 후에만).
    balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_credit_wallets_user UNIQUE (user_id),
    CONSTRAINT ck_credit_wallets_balance CHECK (balance >= 0)
);

CREATE TABLE credit_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 부호 있는 증감. 적립·환불은 양수, 소모는 음수. balance = SUM(amount).
    amount BIGINT NOT NULL,
    reason VARCHAR(30) NOT NULL,
    -- 소모 행은 연관 리소스(STORY·CHAT·AI_CALL_LOG), REFUND 행은 소모 행(CREDIT_TRANSACTION)을 가리킨다.
    ref_type VARCHAR(30),
    ref_id BIGINT,
    -- 보상 멱등 키(signup:{userId}·attendance:{userId}:{KST날짜}·invite:{초대자}:{피초대자}). 소모/환불 행은 NULL.
    idempotency_key VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL은 서로 다른 값으로 취급되므로 소모/환불(NULL 키) 행은 충돌하지 않는다. 보상 행만 키로 중복 차단.
    CONSTRAINT uq_credit_transactions_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_credit_transactions_amount CHECK (amount <> 0),
    CONSTRAINT ck_credit_transactions_reason CHECK (
        reason IN ('SIGNUP_REWARD', 'INVITE_REWARD', 'ATTENDANCE_REWARD', 'STORY_CREATION', 'CHAT_TURN', 'REFUND', 'PURCHASE')
    )
);

CREATE INDEX idx_credit_transactions_user ON credit_transactions(user_id);
