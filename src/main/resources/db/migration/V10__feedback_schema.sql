CREATE TABLE feedbacks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    body TEXT NOT NULL,
    email VARCHAR(320),
    platform VARCHAR(20),
    app_version VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_feedbacks_body_not_blank CHECK (length(btrim(body)) > 0),
    CONSTRAINT ck_feedbacks_platform CHECK (platform IS NULL OR platform IN ('IOS', 'ANDROID', 'WEB'))
);

CREATE INDEX idx_feedbacks_created_at ON feedbacks(created_at DESC);
