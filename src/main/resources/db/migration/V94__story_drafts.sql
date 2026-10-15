CREATE TABLE story_drafts (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    mode VARCHAR(255) NOT NULL,
    stage VARCHAR(255) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    title TEXT,
    one_line_intro TEXT,
    thumbnail_object_key TEXT,
    generation_request_id UUID REFERENCES story_creation_requests(request_id),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_story_drafts_mode_stage CHECK (
        (mode = 'GENERAL' AND stage = 'GENERAL_DRAFT') OR
        (mode = 'SIMPLE' AND stage IN ('KEYWORD_DRAFT', 'STORY_DRAFT'))
    ),
    CONSTRAINT ck_story_drafts_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_story_drafts_version CHECK (version >= 1)
);
CREATE INDEX ix_story_drafts_user_created ON story_drafts(user_id, created_at DESC, id);
