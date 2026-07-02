-- 로어북(장르 공용 용어 사전) 카탈로그. 트리거 키워드가 있는 키워드북과는 별개 개념.
CREATE TABLE lorebooks (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    genre VARCHAR(50),
    content TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_lorebooks_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX idx_lorebooks_genre_active ON lorebooks (genre, is_active, sort_order, id);

-- 스토리가 참조하는 로어북(다대다 조인).
CREATE TABLE story_lorebooks (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    lorebook_id BIGINT NOT NULL REFERENCES lorebooks(id) ON DELETE CASCADE,
    sort_order SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_lorebooks_story_lorebook UNIQUE (story_id, lorebook_id),
    CONSTRAINT ck_story_lorebooks_sort_order CHECK (sort_order > 0)
);

-- 스토리 엔딩(스토리 소유 1:N). condition_text는 도달 조건 자유 텍스트로 저장만 한다.
CREATE TABLE story_endings (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    title VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    condition_text TEXT,
    sort_order SMALLINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_endings_order UNIQUE (story_id, sort_order),
    CONSTRAINT ck_story_endings_order CHECK (sort_order > 0)
);
