-- 용어집(KNK-360) 9번: story_play_sessions는 채팅(이어쓰기 세션)을 담는데 데이터 계층만
-- play_session으로 남아 있어 chat 계열로 정렬한다. "session"은 분석 세션 예약어(§0-5)라 쓰지 않는다.
-- RENAME만 수행하므로 데이터 이동은 없다. FK 컬럼 chat_id는 내부 PK(BIGINT)로, 로그·분석의
-- chat_id(public UUID)와 층위가 다르다(story_id FK와 동일한 기존 구조).

-- 1) 테이블·시퀀스
ALTER TABLE story_play_sessions RENAME TO story_chats;
ALTER SEQUENCE story_play_sessions_id_seq RENAME TO story_chats_id_seq;

-- 2) story_chats 제약·인덱스 (컬럼 rename이 없어 RENAME으로 충분)
ALTER TABLE story_chats RENAME CONSTRAINT story_play_sessions_pkey TO story_chats_pkey;
ALTER TABLE story_chats RENAME CONSTRAINT story_play_sessions_story_id_fkey TO story_chats_story_id_fkey;
ALTER TABLE story_chats RENAME CONSTRAINT story_play_sessions_start_setting_id_fkey TO story_chats_start_setting_id_fkey;
ALTER TABLE story_chats RENAME CONSTRAINT ck_story_play_sessions_status TO ck_story_chats_status;
ALTER TABLE story_chats RENAME CONSTRAINT ck_story_play_sessions_current_turn TO ck_story_chats_current_turn;
ALTER TABLE story_chats RENAME CONSTRAINT uq_story_play_sessions_public_id TO uq_story_chats_public_id;
ALTER INDEX idx_story_play_sessions_story RENAME TO idx_story_chats_story;

-- 3) story_messages FK 컬럼
ALTER TABLE story_messages RENAME COLUMN play_session_id TO chat_id;
ALTER TABLE story_messages RENAME CONSTRAINT story_messages_play_session_id_fkey TO story_messages_chat_id_fkey;
-- rename된 컬럼을 덮는 UNIQUE는 재생성한다: RENAME COLUMN은 인덱스 릴레이션의 attribute 이름을
-- 갱신하지 않아(pg_attribute 잔존) tbls 같은 카탈로그 조회 도구에 옛 컬럼명이 남기 때문이다(V20 선례).
ALTER TABLE story_messages DROP CONSTRAINT uq_story_messages_order;
ALTER TABLE story_messages ADD CONSTRAINT uq_story_messages_order UNIQUE (chat_id, message_order);

-- 4) story_choices FK 컬럼
ALTER TABLE story_choices RENAME COLUMN play_session_id TO chat_id;
ALTER TABLE story_choices RENAME CONSTRAINT story_choices_play_session_id_fkey TO story_choices_chat_id_fkey;
DROP INDEX idx_story_choices_play_session;
CREATE INDEX idx_story_choices_chat ON story_choices(chat_id);
