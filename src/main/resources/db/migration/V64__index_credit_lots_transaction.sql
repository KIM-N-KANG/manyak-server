-- KNK-1044: 크레딧 이용내역 조회가 획득 행의 만료일을 credit_lots.transaction_id 배치 조회(findByTransactionIdIn)로
-- 해석한다. 기존 인덱스는 PK와 idx_credit_lots_user_active(user_id, expires_at) WHERE remaining > 0 뿐이라
-- 이 새 접근 경로가 전체 스캔이 된다.
-- UNIQUE로 걸지 않는다: 적립·환불 1건이 로트 1개라 코드상 1:1이 맞지만, 운영 데이터에 중복이 하나라도 있으면
-- Flyway가 실패해 앱이 뜨지 않는다. 조회 성능이 목적이므로 plain 인덱스로 충분하다.
CREATE INDEX idx_credit_lots_transaction ON credit_lots (transaction_id);
