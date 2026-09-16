-- 이미지 소모는 턴과 독립된 원장 행이며 ref_type=CHAT_IMAGE로 대사 그룹을 분리한다.
ALTER TABLE credit_transactions DROP CONSTRAINT ck_credit_transactions_reason;
ALTER TABLE credit_transactions ADD CONSTRAINT ck_credit_transactions_reason CHECK (
    reason IN ('SIGNUP_REWARD', 'INVITE_REWARD', 'ATTENDANCE_REWARD', 'STORY_CREATION', 'CHAT_TURN', 'CHAT_IMAGE', 'REFUND', 'PURCHASE', 'EXPIRE', 'PURCHASE_REVERSAL')
);
