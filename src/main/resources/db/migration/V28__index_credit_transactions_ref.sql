-- 크레딧 대사 배치(KNK-448)의 그룹 조회·환불 카운트를 위한 인덱스.
-- 배치는 소모 행을 (user_id, ref_type, ref_id)로 묶어 charge 수를 세고, 같은 키의 REFUND 수를 재확인한다.
-- 기존 idx_credit_transactions_user(user_id)만으로는 ref 기준 집계가 전체 스캔이 되므로 복합 인덱스를 추가한다.
CREATE INDEX idx_credit_transactions_ref ON credit_transactions (user_id, ref_type, ref_id);
