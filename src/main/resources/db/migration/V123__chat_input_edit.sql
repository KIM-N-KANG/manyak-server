ALTER TABLE story_messages ADD COLUMN hidden_at timestamptz;
ALTER TABLE story_messages DROP CONSTRAINT uq_story_messages_order;
CREATE UNIQUE INDEX uq_story_messages_order_visible ON story_messages(chat_id, message_order) WHERE hidden_at IS NULL;
ALTER TABLE story_chats ADD COLUMN hidden_turn_count integer NOT NULL DEFAULT 0 CHECK (hidden_turn_count >= 0);
