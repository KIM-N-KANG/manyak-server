-- 구매 환불 회수는 음수 원장 행으로 남긴다. V39 사유 제약을 확장한다.
ALTER TABLE credit_transactions DROP CONSTRAINT ck_credit_transactions_reason;
ALTER TABLE credit_transactions ADD CONSTRAINT ck_credit_transactions_reason CHECK (
    reason IN ('SIGNUP_REWARD', 'INVITE_REWARD', 'ATTENDANCE_REWARD', 'STORY_CREATION', 'CHAT_TURN', 'REFUND', 'PURCHASE', 'EXPIRE', 'PURCHASE_REVERSAL')
);

-- 완료보다 먼저 도착한 전체 환불의 표식. 완료 처리 시 적립 없이 환불 상태로 전이하고 삭제한다.
CREATE TABLE groble_refund_marks (
    merchant_uid VARCHAR(255) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    refund_amount BIGINT
);

-- 회수 시 이미 소진돼 돌려받지 못한 수량. NULL은 회수 미수행, 0은 전량 회수.
ALTER TABLE credit_orders ADD COLUMN reversal_shortfall BIGINT;
