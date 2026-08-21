-- KNK-845: 간편 제작 입력을 태그 나열에서 주인공·주변 인물 단위로 저장한다.
-- 장르 태그는 character_id가 NULL이고, 인물 특징 태그는 해당 인물을 가리킨다.

CREATE TABLE story_creation_characters (
    id BIGSERIAL PRIMARY KEY,
    creation_session_id BIGINT NOT NULL REFERENCES story_creation_sessions(id) ON DELETE CASCADE,
    role VARCHAR(30) NOT NULL,
    name VARCHAR(30),
    gender VARCHAR(10),
    sort_order SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_story_creation_characters_role
        CHECK (role IN ('PROTAGONIST', 'SUPPORTING_CHARACTER')),
    CONSTRAINT ck_story_creation_characters_gender
        CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE')),
    CONSTRAINT ck_story_creation_characters_sort_order
        CHECK (sort_order > 0),
    CONSTRAINT ck_story_creation_characters_protagonist_order
        CHECK (role <> 'PROTAGONIST' OR sort_order = 1),
    CONSTRAINT uq_story_creation_characters_role_order
        UNIQUE (creation_session_id, role, sort_order)
);

CREATE UNIQUE INDEX uq_story_creation_characters_protagonist
    ON story_creation_characters (creation_session_id)
    WHERE role = 'PROTAGONIST';

ALTER TABLE story_creation_session_tags
    ADD COLUMN character_id BIGINT REFERENCES story_creation_characters(id) ON DELETE CASCADE;

-- 같은 특징 태그를 서로 다른 인물에게 붙일 수 있어야 한다. NULLS NOT DISTINCT로 장르(NULL)도
-- 종전처럼 세션 안에서 중복되지 않게 하면서, 인물별 특징 연결의 유니크 범위를 분리한다(PostgreSQL 16).
ALTER TABLE story_creation_session_tags
    DROP CONSTRAINT uq_story_creation_session_tags_tag;

ALTER TABLE story_creation_session_tags
    ADD CONSTRAINT uq_story_creation_session_tags_character_tag
        UNIQUE NULLS NOT DISTINCT (creation_session_id, character_id, tag_id);

CREATE INDEX idx_story_creation_session_tags_character_id
    ON story_creation_session_tags (character_id);
