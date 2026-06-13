CREATE TABLE story_creation_tags (
    id BIGSERIAL PRIMARY KEY,
    tag_type VARCHAR(50) NOT NULL,
    name VARCHAR(30) NOT NULL,
    tag_source VARCHAR(20) NOT NULL,
    sort_order INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_story_creation_tags_tag_type
        CHECK (tag_type IN ('GENRE', 'PROTAGONIST', 'SUPPORTING_CHARACTER')),
    CONSTRAINT ck_story_creation_tags_tag_source
        CHECK (tag_source IN ('PREDEFINED', 'CUSTOM')),
    CONSTRAINT ck_story_creation_tags_sort_order
        CHECK (sort_order >= 0)
);

CREATE INDEX idx_story_creation_tags_lookup
    ON story_creation_tags (tag_source, is_active, tag_type, sort_order, id);

CREATE TABLE story_creation_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    story_id BIGINT,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_story_creation_sessions_status
        CHECK (status IN ('STORYLINES_GENERATED', 'STORY_CREATED'))
);

CREATE TABLE story_creation_session_tags (
    id BIGSERIAL PRIMARY KEY,
    creation_session_id BIGINT NOT NULL REFERENCES story_creation_sessions(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES story_creation_tags(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_creation_session_tags_tag
        UNIQUE (creation_session_id, tag_id)
);

CREATE TABLE story_creation_examples (
    id BIGSERIAL PRIMARY KEY,
    creation_session_id BIGINT NOT NULL REFERENCES story_creation_sessions(id) ON DELETE CASCADE,
    example_text TEXT NOT NULL,
    example_order SMALLINT NOT NULL,
    is_selected BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_creation_examples_order
        UNIQUE (creation_session_id, example_order),
    CONSTRAINT ck_story_creation_examples_order
        CHECK (example_order > 0)
);

CREATE TABLE story_creation_example_questions (
    id BIGSERIAL PRIMARY KEY,
    example_id BIGINT NOT NULL REFERENCES story_creation_examples(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    question_order SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_creation_example_questions_order
        UNIQUE (example_id, question_order),
    CONSTRAINT ck_story_creation_example_questions_order
        CHECK (question_order > 0)
);

INSERT INTO story_creation_tags (tag_type, name, tag_source, sort_order)
VALUES
    ('GENRE', '로맨스', 'PREDEFINED', 10),
    ('GENRE', '현대', 'PREDEFINED', 20),
    ('GENRE', '무협', 'PREDEFINED', 30),
    ('GENRE', '판타지', 'PREDEFINED', 40),
    ('GENRE', '게임', 'PREDEFINED', 50),
    ('GENRE', '학원', 'PREDEFINED', 60),
    ('GENRE', '추리', 'PREDEFINED', 70),
    ('GENRE', '헌터', 'PREDEFINED', 80),
    ('GENRE', '회귀', 'PREDEFINED', 90),
    ('GENRE', '빙의', 'PREDEFINED', 100),
    ('GENRE', '환생', 'PREDEFINED', 110),
    ('GENRE', '미스터리', 'PREDEFINED', 120),
    ('GENRE', '재벌', 'PREDEFINED', 130),
    ('GENRE', '복수', 'PREDEFINED', 140),
    ('GENRE', '정치', 'PREDEFINED', 150),
    ('GENRE', '중세', 'PREDEFINED', 160),
    ('GENRE', '오피스', 'PREDEFINED', 170),
    ('GENRE', '궁중', 'PREDEFINED', 180),
    ('GENRE', '계약', 'PREDEFINED', 190),
    ('GENRE', '포스트 아포칼립스', 'PREDEFINED', 200),
    ('GENRE', '육아', 'PREDEFINED', 210),
    ('GENRE', '악녀', 'PREDEFINED', 220),
    ('GENRE', '탑', 'PREDEFINED', 230),
    ('GENRE', '던전', 'PREDEFINED', 240),
    ('GENRE', '스릴러', 'PREDEFINED', 250),
    ('GENRE', '좀비', 'PREDEFINED', 260),
    ('GENRE', '생존', 'PREDEFINED', 270),
    ('GENRE', '힐링', 'PREDEFINED', 280),
    ('PROTAGONIST', '집착적인', 'PREDEFINED', 10),
    ('PROTAGONIST', '무심한', 'PREDEFINED', 20),
    ('PROTAGONIST', '능글맞은', 'PREDEFINED', 30),
    ('PROTAGONIST', '정의로운', 'PREDEFINED', 40),
    ('PROTAGONIST', '소심한', 'PREDEFINED', 50),
    ('PROTAGONIST', '계산적인', 'PREDEFINED', 60),
    ('PROTAGONIST', '당돌한', 'PREDEFINED', 70),
    ('PROTAGONIST', '오만한', 'PREDEFINED', 80),
    ('PROTAGONIST', '복수심 강한', 'PREDEFINED', 90),
    ('PROTAGONIST', '자기희생적인', 'PREDEFINED', 100),
    ('PROTAGONIST', '야망 있는', 'PREDEFINED', 110),
    ('PROTAGONIST', '냉소적인', 'PREDEFINED', 120),
    ('PROTAGONIST', '낙천적인', 'PREDEFINED', 130),
    ('PROTAGONIST', '비밀스러운', 'PREDEFINED', 140),
    ('PROTAGONIST', '순진한', 'PREDEFINED', 150),
    ('PROTAGONIST', '강단있는', 'PREDEFINED', 160),
    ('PROTAGONIST', '눈치 빠른', 'PREDEFINED', 170),
    ('PROTAGONIST', '허당끼 있는', 'PREDEFINED', 180),
    ('PROTAGONIST', '겁 없는', 'PREDEFINED', 190),
    ('PROTAGONIST', '상처 많은', 'PREDEFINED', 200),
    ('PROTAGONIST', '독립적인', 'PREDEFINED', 210),
    ('PROTAGONIST', '이성적인', 'PREDEFINED', 220),
    ('PROTAGONIST', '책임감 있는', 'PREDEFINED', 230),
    ('PROTAGONIST', '감정적인', 'PREDEFINED', 240),
    ('PROTAGONIST', '집요한', 'PREDEFINED', 250),
    ('PROTAGONIST', '배려심 깊은', 'PREDEFINED', 260),
    ('PROTAGONIST', '반항적인', 'PREDEFINED', 270),
    ('PROTAGONIST', '성장형', 'PREDEFINED', 280),
    ('SUPPORTING_CHARACTER', '서늘한', 'PREDEFINED', 10),
    ('SUPPORTING_CHARACTER', '햇살 같은', 'PREDEFINED', 20),
    ('SUPPORTING_CHARACTER', '퇴폐적인', 'PREDEFINED', 30),
    ('SUPPORTING_CHARACTER', '고귀한', 'PREDEFINED', 40),
    ('SUPPORTING_CHARACTER', '위험한', 'PREDEFINED', 50),
    ('SUPPORTING_CHARACTER', '신비로운', 'PREDEFINED', 60),
    ('SUPPORTING_CHARACTER', '처연한', 'PREDEFINED', 70),
    ('SUPPORTING_CHARACTER', '우아한', 'PREDEFINED', 80),
    ('SUPPORTING_CHARACTER', '광기 어린', 'PREDEFINED', 90),
    ('SUPPORTING_CHARACTER', '청량한', 'PREDEFINED', 100),
    ('SUPPORTING_CHARACTER', '음침한', 'PREDEFINED', 110),
    ('SUPPORTING_CHARACTER', '몽환적인', 'PREDEFINED', 120),
    ('SUPPORTING_CHARACTER', '섹시한', 'PREDEFINED', 130),
    ('SUPPORTING_CHARACTER', '단정한', 'PREDEFINED', 140),
    ('SUPPORTING_CHARACTER', '거친', 'PREDEFINED', 150),
    ('SUPPORTING_CHARACTER', '순수한', 'PREDEFINED', 160),
    ('SUPPORTING_CHARACTER', '압도적인', 'PREDEFINED', 170),
    ('SUPPORTING_CHARACTER', '나른한', 'PREDEFINED', 180),
    ('SUPPORTING_CHARACTER', '예민한', 'PREDEFINED', 190),
    ('SUPPORTING_CHARACTER', '권태로운', 'PREDEFINED', 200),
    ('SUPPORTING_CHARACTER', '귀여운', 'PREDEFINED', 210),
    ('SUPPORTING_CHARACTER', '성숙한', 'PREDEFINED', 220),
    ('SUPPORTING_CHARACTER', '비극적인', 'PREDEFINED', 230),
    ('SUPPORTING_CHARACTER', '위압적인', 'PREDEFINED', 240),
    ('SUPPORTING_CHARACTER', '친근한', 'PREDEFINED', 250),
    ('SUPPORTING_CHARACTER', '이질적인', 'PREDEFINED', 260),
    ('SUPPORTING_CHARACTER', '귀족적인', 'PREDEFINED', 270),
    ('SUPPORTING_CHARACTER', '반듯한', 'PREDEFINED', 280),
    ('SUPPORTING_CHARACTER', '짐승 같은', 'PREDEFINED', 290),
    ('SUPPORTING_CHARACTER', '보호본능을 자극하는', 'PREDEFINED', 300);
