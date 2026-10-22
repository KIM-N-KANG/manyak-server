CREATE TABLE chat_models (
    code varchar(20) PRIMARY KEY,
    name varchar(30) NOT NULL,
    turn_cost bigint NOT NULL CHECK (turn_cost BETWEEN 1 AND 10000),
    sort_order integer NOT NULL CHECK (sort_order >= 1),
    is_active boolean NOT NULL DEFAULT true,
    is_default boolean NOT NULL DEFAULT false CHECK (is_active OR NOT is_default),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_chat_models_default ON chat_models (is_default) WHERE is_default;
INSERT INTO chat_models (code, name, turn_cost, sort_order, is_default) VALUES
    ('LITE', '라이트', 20, 1, false), ('AURORA', '오로라', 50, 2, true);
ALTER TABLE story_chats ADD COLUMN model_code varchar(20) NOT NULL DEFAULT 'LITE' REFERENCES chat_models(code);
ALTER TABLE story_messages ADD COLUMN credit_transaction_id bigint REFERENCES credit_transactions(id);
ALTER TABLE story_message_versions ADD COLUMN credit_transaction_id bigint REFERENCES credit_transactions(id);
CREATE UNIQUE INDEX uq_story_messages_credit_transaction ON story_messages (credit_transaction_id) WHERE credit_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_story_message_versions_credit_transaction ON story_message_versions (credit_transaction_id) WHERE credit_transaction_id IS NOT NULL;
CREATE INDEX idx_credit_transactions_chat_turn_created ON credit_transactions (id) WHERE ref_type = 'CHAT_TURN';
