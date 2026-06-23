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

CREATE TABLE story_creation_example_recommended_infos (
    id BIGSERIAL PRIMARY KEY,
    example_id BIGINT NOT NULL REFERENCES story_creation_examples(id) ON DELETE CASCADE,
    info_text TEXT NOT NULL,
    info_order SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_creation_example_recommended_infos_order
        UNIQUE (example_id, info_order),
    CONSTRAINT ck_story_creation_example_recommended_infos_order
        CHECK (info_order > 0)
);

-- 기본(PREDEFINED) 태그 시드. 출처: "한국 10대~30대 독자층이 선호하는 웹툰·웹소설 서사 패턴 연구"
-- 의미 중복을 제거해 한 개념당 하나만 남긴 핵심 세트. 장르 10 / 주인공 특성 10 / 주변 인물 특성 10.
INSERT INTO story_creation_tags (tag_type, name, tag_source, sort_order)
VALUES
    ('GENRE', '현대판타지', 'PREDEFINED', 10),
    ('GENRE', '로맨스판타지', 'PREDEFINED', 20),
    ('GENRE', '무협', 'PREDEFINED', 30),
    ('GENRE', '회귀', 'PREDEFINED', 40),
    ('GENRE', '환생', 'PREDEFINED', 50),
    ('GENRE', '시스템', 'PREDEFINED', 60),
    ('GENRE', '학원', 'PREDEFINED', 70),
    ('GENRE', '악역', 'PREDEFINED', 80),
    ('GENRE', '괴담', 'PREDEFINED', 90),
    ('GENRE', '시한부', 'PREDEFINED', 100),
    ('PROTAGONIST', '천재적인', 'PREDEFINED', 10),
    ('PROTAGONIST', '먼치킨', 'PREDEFINED', 20),
    ('PROTAGONIST', '힘숨찐', 'PREDEFINED', 30),
    ('PROTAGONIST', '계략적인', 'PREDEFINED', 40),
    ('PROTAGONIST', '성장하는', 'PREDEFINED', 50),
    ('PROTAGONIST', '복수하는', 'PREDEFINED', 60),
    ('PROTAGONIST', '정의로운', 'PREDEFINED', 70),
    ('PROTAGONIST', '책임감 있는', 'PREDEFINED', 80),
    ('PROTAGONIST', '헌신적인', 'PREDEFINED', 90),
    ('PROTAGONIST', '사연 있는', 'PREDEFINED', 100),
    ('SUPPORTING_CHARACTER', '집착하는', 'PREDEFINED', 10),
    ('SUPPORTING_CHARACTER', '흑막', 'PREDEFINED', 20),
    ('SUPPORTING_CHARACTER', '후회하는', 'PREDEFINED', 30),
    ('SUPPORTING_CHARACTER', '라이벌', 'PREDEFINED', 40),
    ('SUPPORTING_CHARACTER', '동료', 'PREDEFINED', 50),
    ('SUPPORTING_CHARACTER', '스승', 'PREDEFINED', 60),
    ('SUPPORTING_CHARACTER', '귀족', 'PREDEFINED', 70),
    ('SUPPORTING_CHARACTER', '악녀', 'PREDEFINED', 80),
    ('SUPPORTING_CHARACTER', '초월적인', 'PREDEFINED', 90),
    ('SUPPORTING_CHARACTER', '사랑스러운', 'PREDEFINED', 100);
