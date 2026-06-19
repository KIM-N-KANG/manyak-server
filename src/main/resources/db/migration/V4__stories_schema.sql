CREATE TABLE stories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    title VARCHAR(100) NOT NULL,
    one_line_intro VARCHAR(255),
    description TEXT,
    genre VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE story_settings (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    world_setting TEXT NOT NULL,
    character_setting TEXT NOT NULL,
    user_role_setting TEXT,
    rule_setting TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_settings_story UNIQUE (story_id)
);

CREATE TABLE story_start_settings (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    prologue TEXT,
    start_situation TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_start_settings_story UNIQUE (story_id)
);

CREATE TABLE story_suggested_inputs (
    id BIGSERIAL PRIMARY KEY,
    start_setting_id BIGINT NOT NULL REFERENCES story_start_settings(id) ON DELETE CASCADE,
    input_text TEXT NOT NULL,
    input_order SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_suggested_inputs_order
        UNIQUE (start_setting_id, input_order),
    CONSTRAINT ck_story_suggested_inputs_order
        CHECK (input_order > 0)
);
