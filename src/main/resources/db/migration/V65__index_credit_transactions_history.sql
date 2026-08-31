-- 크레딧 이용내역 조회(KNK-1044)의 정렬 키를 덮는 복합 인덱스.
-- GET /api/v1/users/me/credits/transactions 는 (user_id) 로 좁힌 뒤 created_at DESC, id DESC 로 커서 페이징한다.
-- 기존 인덱스는 idx_credit_transactions_user(user_id) 와 idx_credit_transactions_ref(user_id, ref_type, ref_id) 뿐이라,
-- 채팅 턴마다 원장이 쌓이는 활성 사용자에서는 limit 이 작아도 후보 행 전체를 읽고 정렬해야 한다.
CREATE INDEX idx_credit_transactions_user_history
    ON credit_transactions (user_id, created_at DESC, id DESC);
