-- 인기순, 채팅순 정렬과 카드, 상세의 누적 턴 합계가 힙을 읽지 않고 Index Only Scan으로 끝나게 한다(KNK-2076).
-- 키가 story_id 하나라 기존 idx_story_chats_story의 역할(FK 조회 포함)을 그대로 맡으므로 교체한다.
-- 턴 저장은 이미 HOT 갱신이 아니어서(updated_at, current_turn이 다른 인덱스에 있음) 교체로 인덱스 쓰기 수가 늘지 않는다.
-- story_chats는 운영에서 수천 행이라 일반 CREATE INDEX의 쓰기 차단이 짧다. 한 트랜잭션에서 만든 뒤 지운다.
-- DROP INDEX는 CREATE INDEX가 잡은 SHARE를 ACCESS EXCLUSIVE로 올린다. 진행 중 트랜잭션을 상한 없이 기다리면
-- 뒤따르는 story_chats 쿼리가 모두 줄을 서고, FOR UPDATE로 읽은 뒤 갱신하는 턴 저장과 교착될 수 있다.
-- 그래서 잠금 대기에 5초 상한을 둔다. 넘으면 이 트랜잭션이 롤백되고 기동 실패로 드러나므로 다시 배포하면 된다.
SET LOCAL lock_timeout = '5s';
CREATE INDEX idx_story_chats_story_turns ON story_chats (story_id)
    INCLUDE (current_turn, hidden_turn_count, regenerated_count, forked_from_turn);
DROP INDEX idx_story_chats_story;
