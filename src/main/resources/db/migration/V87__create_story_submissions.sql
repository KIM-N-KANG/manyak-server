CREATE TABLE story_submissions (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_id BIGINT REFERENCES stories(id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('CREATE', 'UPDATE')),
    payload JSONB NOT NULL,
    input_form JSONB NOT NULL,
    image_copies JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(image_copies) = 'object'),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'FAILED')),
    issues JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_code VARCHAR(40),
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt > 0),
    dispatched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    CONSTRAINT ck_story_submissions_target CHECK (kind = 'CREATE' OR story_id IS NOT NULL),
    CONSTRAINT ck_story_submissions_approved_target CHECK (status <> 'APPROVED' OR story_id IS NOT NULL),
    CONSTRAINT ck_story_submissions_decision CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT ck_story_submissions_error CHECK ((status = 'FAILED') = (error_code IS NOT NULL)),
    CONSTRAINT ck_story_submissions_json CHECK (jsonb_typeof(payload) = 'object' AND jsonb_typeof(issues) = 'array')
);
CREATE UNIQUE INDEX uq_story_submissions_pending ON story_submissions(story_id) WHERE story_id IS NOT NULL AND status = 'PENDING';
CREATE UNIQUE INDEX uq_story_submissions_unapproved ON story_submissions(story_id) WHERE story_id IS NOT NULL AND status <> 'APPROVED';
CREATE INDEX ix_story_submissions_owner ON story_submissions(user_id, created_at DESC, id DESC);
CREATE INDEX ix_story_submissions_reclaim ON story_submissions(dispatched_at) WHERE status = 'PENDING';
COMMENT ON TABLE story_submissions IS '일반 제작 등록·수정 검수 제출본. 승인 전 라이브와 분리';
