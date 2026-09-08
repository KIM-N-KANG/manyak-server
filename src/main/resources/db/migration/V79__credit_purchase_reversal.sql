-- 구매 환불 회수는 음수 원장 행으로 남긴다. V39 사유 제약을 확장한다.
ALTER TABLE credit_transactions DROP CONSTRAINT ck_credit_transactions_reason;
ALTER TABLE credit_transactions ADD CONSTRAINT ck_credit_transactions_reason CHECK (
    reason IN ('SIGNUP_REWARD', 'INVITE_REWARD', 'ATTENDANCE_REWARD', 'STORY_CREATION', 'CHAT_TURN', 'REFUND', 'PURCHASE', 'EXPIRE', 'PURCHASE_REVERSAL')
);
