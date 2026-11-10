-- 인기순, 채팅순 정렬과 카드, 상세의 누적 턴 합계가 힙을 읽지 않고 Index Only Scan으로 끝나게 한다(KNK-2076).
-- 키가 story_id 하나라 기존 idx_story_chats_story의 역할(FK 조회 포함)을 그대로 맡으므로 교체한다.
-- 턴 저장은 이미 HOT 갱신이 아니어서(updated_at, current_turn이 다른 인덱스에 있음) 교체로 인덱스 쓰기 수가 늘지 않는다.
-- story_chats는 운영에서 수천 행이라 일반 CREATE INDEX의 쓰기 차단이 짧다. 한 트랜잭션에서 만든 뒤 지운다.
CREATE INDEX idx_story_chats_story_turns ON story_chats (story_id)
    INCLUDE (current_turn, hidden_turn_count, regenerated_count, forked_from_turn);
DROP INDEX idx_story_chats_story;
