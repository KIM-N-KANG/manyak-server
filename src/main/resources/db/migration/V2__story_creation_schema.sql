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
-- 장르 20 / 주인공 특성 20 / 주변 인물 특성 20.
INSERT INTO story_creation_tags (tag_type, name, tag_source, sort_order)
VALUES
    ('GENRE', '판타지', 'PREDEFINED', 10),
    ('GENRE', '현대판타지', 'PREDEFINED', 20),
    ('GENRE', '로맨스판타지', 'PREDEFINED', 30),
    ('GENRE', '무협', 'PREDEFINED', 40),
    ('GENRE', '회귀물', 'PREDEFINED', 50),
    ('GENRE', '빙의물', 'PREDEFINED', 60),
    ('GENRE', '환생물', 'PREDEFINED', 70),
    ('GENRE', '헌터물', 'PREDEFINED', 80),
    ('GENRE', '게이트/던전', 'PREDEFINED', 90),
    ('GENRE', '상태창/시스템', 'PREDEFINED', 100),
    ('GENRE', '아카데미/학원물', 'PREDEFINED', 110),
    ('GENRE', '가문/명가물', 'PREDEFINED', 120),
    ('GENRE', '재벌물', 'PREDEFINED', 130),
    ('GENRE', '악역/망나니물', 'PREDEFINED', 140),
    ('GENRE', '복수물', 'PREDEFINED', 150),
    ('GENRE', '성장물', 'PREDEFINED', 160),
    ('GENRE', '육아물', 'PREDEFINED', 170),
    ('GENRE', '시한부물', 'PREDEFINED', 180),
    ('GENRE', '스트리밍/방송·연예계물', 'PREDEFINED', 190),
    ('GENRE', '괴담/스릴러·아포칼립스', 'PREDEFINED', 200),
    ('PROTAGONIST', '회귀자', 'PREDEFINED', 10),
    ('PROTAGONIST', '빙의자', 'PREDEFINED', 20),
    ('PROTAGONIST', '환생자', 'PREDEFINED', 30),
    ('PROTAGONIST', '조연/엑스트라형', 'PREDEFINED', 40),
    ('PROTAGONIST', '악역/망나니형', 'PREDEFINED', 50),
    ('PROTAGONIST', '천재형', 'PREDEFINED', 60),
    ('PROTAGONIST', '시한부형', 'PREDEFINED', 70),
    ('PROTAGONIST', '헌터/랭커형', 'PREDEFINED', 80),
    ('PROTAGONIST', '전문직형', 'PREDEFINED', 90),
    ('PROTAGONIST', '아이돌/배우/매니저형', 'PREDEFINED', 100),
    ('PROTAGONIST', '외유내강형', 'PREDEFINED', 110),
    ('PROTAGONIST', '먼치킨형', 'PREDEFINED', 120),
    ('PROTAGONIST', '성장형', 'PREDEFINED', 130),
    ('PROTAGONIST', '복수형', 'PREDEFINED', 140),
    ('PROTAGONIST', '생존형', 'PREDEFINED', 150),
    ('PROTAGONIST', '계략형', 'PREDEFINED', 160),
    ('PROTAGONIST', '절대선/책임형', 'PREDEFINED', 170),
    ('PROTAGONIST', '숨은 실력자형', 'PREDEFINED', 180),
    ('PROTAGONIST', '보호자형', 'PREDEFINED', 190),
    ('PROTAGONIST', '사연캐형', 'PREDEFINED', 200),
    ('SUPPORTING_CHARACTER', '집착형 상대역', 'PREDEFINED', 10),
    ('SUPPORTING_CHARACTER', '흑막', 'PREDEFINED', 20),
    ('SUPPORTING_CHARACTER', '후회형 가족/연인', 'PREDEFINED', 30),
    ('SUPPORTING_CHARACTER', '라이벌', 'PREDEFINED', 40),
    ('SUPPORTING_CHARACTER', '동료/파티원', 'PREDEFINED', 50),
    ('SUPPORTING_CHARACTER', '사부/멘토', 'PREDEFINED', 60),
    ('SUPPORTING_CHARACTER', '기사/호위형', 'PREDEFINED', 70),
    ('SUPPORTING_CHARACTER', '충성형 부하', 'PREDEFINED', 80),
    ('SUPPORTING_CHARACTER', '보호자/아빠/보모', 'PREDEFINED', 90),
    ('SUPPORTING_CHARACTER', '귀족/황족/왕족', 'PREDEFINED', 100),
    ('SUPPORTING_CHARACTER', '가문형 가족군', 'PREDEFINED', 110),
    ('SUPPORTING_CHARACTER', '비서/매니저/오퍼레이터', 'PREDEFINED', 120),
    ('SUPPORTING_CHARACTER', '의사/힐러 조력자', 'PREDEFINED', 130),
    ('SUPPORTING_CHARACTER', '정보상/상단주', 'PREDEFINED', 140),
    ('SUPPORTING_CHARACTER', '천재 서브캐', 'PREDEFINED', 150),
    ('SUPPORTING_CHARACTER', '개그·케미 담당', 'PREDEFINED', 160),
    ('SUPPORTING_CHARACTER', '악녀/악역 서브캐', 'PREDEFINED', 170),
    ('SUPPORTING_CHARACTER', '비밀 신분 캐릭터', 'PREDEFINED', 180),
    ('SUPPORTING_CHARACTER', '신·성좌·정령', 'PREDEFINED', 190),
    ('SUPPORTING_CHARACTER', '마스코트/아기/동물', 'PREDEFINED', 200);
