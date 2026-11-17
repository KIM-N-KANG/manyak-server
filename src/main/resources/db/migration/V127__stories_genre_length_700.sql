-- 간편 제작은 제공 장르를 20개까지 받고 이름은 30자까지라 장르 문자열이 최대 638자다(STORY 3-2, KNK-2092).
-- varchar 길이만 늘리는 변경이라 테이블을 다시 쓰지 않고 인덱스도 다시 만들지 않는다. genre를 읽는 뷰, 인덱스, 트리거는 없다.
-- 그래도 ACCESS EXCLUSIVE를 잠깐 잡으므로 진행 중 트랜잭션을 상한 없이 기다려 stories 쿼리가 줄을 서지 않게 5초 상한을 둔다.
-- 넘으면 이 트랜잭션이 롤백되고 기동 실패로 드러나므로 다시 배포하면 된다(V126과 같음).
SET LOCAL lock_timeout = '5s';
ALTER TABLE stories ALTER COLUMN genre TYPE varchar(700);
