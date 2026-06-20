-- 스토리 소프트 삭제를 위한 deleted_at 컬럼을 추가한다.
-- 행을 물리 삭제하지 않고 삭제 시각만 기록해 자식 데이터(설정·시작 설정·추천 입력)를 보존한다.
-- 조회 경로(목록·상세)는 deleted_at IS NULL만 노출한다.
ALTER TABLE stories ADD COLUMN deleted_at TIMESTAMPTZ;
