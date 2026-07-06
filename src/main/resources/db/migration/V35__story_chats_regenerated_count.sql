-- 재생성(스펙 §4-3-9, KNK-406)으로 교체된 완료 횟수를 누적하는 컬럼을 추가한다.
-- 재생성은 유료(CHAT_TURN 선차감)이지만 current_turn(완료 턴 수)은 올리지 않으므로, 크레딧 선차감 대사
-- (KNK-448)가 완료 수를 current_turn만으로 세면 성공한 재생성 1건이 "누락 charge"로 오인돼 초과 환불된다.
-- 대사의 완료 수 = current_turn + regenerated_count가 되도록 이 카운터를 두어 정상 재생성이 환불되지 않게 한다.
ALTER TABLE story_chats
    ADD COLUMN regenerated_count INT NOT NULL DEFAULT 0;

ALTER TABLE story_chats
    ADD CONSTRAINT ck_story_chats_regenerated_count CHECK (regenerated_count >= 0);
