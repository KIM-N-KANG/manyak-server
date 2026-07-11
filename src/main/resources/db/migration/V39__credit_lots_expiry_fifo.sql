-- 크레딧 만료·FIFO 차감(B12, 스펙 §4-3-7): 보상 크레딧은 적립 30일 뒤 만료되고, 차감은 유효기간 있는 로트를
-- 무기한 크레딧(PURCHASE)보다 먼저, 그중 먼저 적립된(만료 임박) 것부터(FIFO) 소진한다.
-- 로트별 잔여를 credit_lots로 추적하고, 만료는 EXPIRE 원장 행으로 실현해 balance = SUM(amount) 불변식을 유지한다.

-- 만료분을 음수 원장 행(EXPIRE)으로 남겨 잔액 캐시를 낮춘다.
ALTER TABLE credit_transactions DROP CONSTRAINT ck_credit_transactions_reason;
ALTER TABLE credit_transactions ADD CONSTRAINT ck_credit_transactions_reason CHECK (
    reason IN ('SIGNUP_REWARD', 'INVITE_REWARD', 'ATTENDANCE_REWARD', 'STORY_CREATION', 'CHAT_TURN', 'REFUND', 'PURCHASE', 'EXPIRE')
);

CREATE TABLE credit_lots (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 이 로트를 만든 적립·환불 원장 행. 레거시 승계 로트는 NULL.
    transaction_id BIGINT REFERENCES credit_transactions(id),
    -- 적립 당시 금액(불변)과 남은 잔여(FIFO 차감·만료로 감소).
    original_amount BIGINT NOT NULL,
    remaining BIGINT NOT NULL,
    -- 만료 시각. NULL이면 무기한(PURCHASE). 보상·환불은 적립 시점 + 30일.
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_credit_lots_original_positive CHECK (original_amount > 0),
    CONSTRAINT ck_credit_lots_remaining_range CHECK (remaining >= 0 AND remaining <= original_amount)
);

-- 활성(잔여>0) 로트 조회·FIFO 정렬(만료 임박 우선)용.
CREATE INDEX idx_credit_lots_user_active ON credit_lots (user_id, expires_at) WHERE remaining > 0;

-- 레거시 잔액 승계: 기존 지갑 잔액을 무기한 로트 1개로 이관한다. 과거 소모를 FIFO로 재생하지 않고 현재 잔액만
-- 승계하므로(만료 없는 로트) 기존 사용자 잔액이 이 마이그레이션으로 바뀌지 않는다. 신규 적립부터 30일 만료가 적용된다.
INSERT INTO credit_lots (user_id, transaction_id, original_amount, remaining, expires_at, created_at)
SELECT user_id, NULL, balance, balance, NULL, now()
FROM credit_wallets
WHERE balance > 0;
