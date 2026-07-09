-- 엔딩·주요 사건 런타임 상태 스키마(스펙 §4-3-10, KNK-521 · B5-B).
-- 판정은 AI, 상태 저장은 백엔드(D11). 채팅이 향하는 목표 사건·진행 카운터, 거쳐온(완결) 사건 기록,
-- 회원 엔딩 도달 집계, 도달 턴/채팅 표식을 한 번에 신설한다. 이 스키마 위에서 채팅 턴 연동(KNK-522)이 동작한다.

-- 1) 채팅의 목표 사건·진행 상태와 최초 도달 엔딩 가드.
--    target_*: 현재 향해 진행 중인 주요 사건과 진행 턴 수(채팅당 최대 1개).
--    reached_ending_id: 최초 도달 엔딩. 값이 있으면 이후 턴 요청에 엔딩 후보를 싣지 않아 채팅당 최초 1회를 보장한다.
ALTER TABLE story_chats
    ADD COLUMN target_main_event_id BIGINT,
    ADD COLUMN target_progress_turns INT NOT NULL DEFAULT 0,
    ADD COLUMN reached_ending_id BIGINT;

-- 목표 사건·도달 엔딩은 스토리의 주요 사건/엔딩을 가리킨다. 원본이 삭제되면 진행 상태만 비우고 채팅은 유지한다.
ALTER TABLE story_chats
    ADD CONSTRAINT fk_story_chats_target_main_event
        FOREIGN KEY (target_main_event_id) REFERENCES story_main_events (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_story_chats_reached_ending
        FOREIGN KEY (reached_ending_id) REFERENCES story_endings (id) ON DELETE SET NULL;

-- 2) 거쳐온(완결) 주요 사건 기록. 한 채팅에서 같은 사건은 한 번만 완결된다(occurred_main_event_names 재료).
CREATE TABLE story_chat_main_events (
    id            BIGSERIAL PRIMARY KEY,
    chat_id       BIGINT NOT NULL,
    main_event_id BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_story_chat_main_events UNIQUE (chat_id, main_event_id),
    -- 채팅/사건이 물리 삭제되면 기록도 함께 정리한다(고아 행 방지).
    CONSTRAINT fk_story_chat_main_events_chat
        FOREIGN KEY (chat_id) REFERENCES story_chats (id) ON DELETE CASCADE,
    CONSTRAINT fk_story_chat_main_events_main_event
        FOREIGN KEY (main_event_id) REFERENCES story_main_events (id) ON DELETE CASCADE
);

CREATE INDEX idx_story_chat_main_events_chat ON story_chat_main_events (chat_id);

-- 3) 회원 엔딩 도달 집계. (회원, 스토리, 엔딩) 단위 최초 도달 1회를 upsert한다. 게스트는 집계하지 않는다.
--    스토리 상세의 reachedEndings 집계 소스이며, 이관(/auth/migrate) 시 게스트 채팅 도달분도 백필한다.
CREATE TABLE user_story_ending_reaches (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    story_id   BIGINT NOT NULL,
    ending_id  BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_story_ending_reaches UNIQUE (user_id, story_id, ending_id),
    CONSTRAINT fk_user_story_ending_reaches_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_story_ending_reaches_story
        FOREIGN KEY (story_id) REFERENCES stories (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_story_ending_reaches_ending
        FOREIGN KEY (ending_id) REFERENCES story_endings (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_story_ending_reaches_user_story ON user_story_ending_reaches (user_id, story_id);

-- 4) 도달 턴 표식. 엔딩 응답이 된 ASSISTANT 메시지에 도달한 엔딩을 표시한다(턴 상세·SSE completed 노출용).
ALTER TABLE story_messages
    ADD COLUMN reached_ending_id BIGINT;

ALTER TABLE story_messages
    ADD CONSTRAINT fk_story_messages_reached_ending
        FOREIGN KEY (reached_ending_id) REFERENCES story_endings (id) ON DELETE SET NULL;
