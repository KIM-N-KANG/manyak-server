ALTER TABLE story_chats
    ADD COLUMN forked_from_chat_id BIGINT,
    ADD COLUMN forked_from_turn INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_story_chats_forked_from_chat
        FOREIGN KEY (forked_from_chat_id) REFERENCES story_chats(id),
    ADD CONSTRAINT ck_story_chats_forked_from_turn CHECK (forked_from_turn >= 0);
