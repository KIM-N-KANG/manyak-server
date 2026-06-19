CREATE TABLE story_play_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    start_setting_id BIGINT REFERENCES story_start_settings(id) ON DELETE SET NULL,
    title VARCHAR(100),
    summary TEXT,
    current_turn INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,

    CONSTRAINT ck_story_play_sessions_status CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_story_play_sessions_current_turn CHECK (current_turn >= 0)
);

CREATE INDEX idx_story_play_sessions_story ON story_play_sessions(story_id);

CREATE TABLE story_messages (
    id BIGSERIAL PRIMARY KEY,
    play_session_id BIGINT NOT NULL REFERENCES story_play_sessions(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    message_order INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_messages_order UNIQUE (play_session_id, message_order),
    CONSTRAINT ck_story_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_story_messages_order CHECK (message_order > 0)
);

CREATE TABLE story_choices (
    id BIGSERIAL PRIMARY KEY,
    play_session_id BIGINT NOT NULL REFERENCES story_play_sessions(id) ON DELETE CASCADE,
    message_id BIGINT NOT NULL REFERENCES story_messages(id) ON DELETE CASCADE,
    choice_text TEXT NOT NULL,
    choice_order SMALLINT NOT NULL,
    is_selected BOOLEAN NOT NULL DEFAULT FALSE,
    selected_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_choices_order UNIQUE (message_id, choice_order),
    CONSTRAINT ck_story_choices_order CHECK (choice_order > 0)
);

CREATE INDEX idx_story_choices_message ON story_choices(message_id);
CREATE INDEX idx_story_choices_play_session ON story_choices(play_session_id);
