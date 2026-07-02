-- 용어집(KNK-360) 9번: story_creation_examples 계열은 AI가 생성한 스토리라인 후보를 담는데
-- 데이터 계층만 example로 남아 있어 storyline 계열로 정렬한다. RENAME만 수행하므로 데이터 이동은 없다.
-- 자동 명명된 PK·FK·시퀀스도 함께 rename해 이름 잔재를 남기지 않는다(dbdoc 표기 일관성).

-- 1) 테이블
ALTER TABLE story_creation_examples RENAME TO story_creation_storylines;
ALTER TABLE story_creation_example_recommended_infos RENAME TO story_creation_storyline_recommended_infos;
ALTER TABLE story_creation_example_ratings RENAME TO story_creation_storyline_ratings;

-- 2) 컬럼
ALTER TABLE story_creation_storylines RENAME COLUMN example_text TO storyline_text;
ALTER TABLE story_creation_storylines RENAME COLUMN example_order TO storyline_order;
ALTER TABLE story_creation_storyline_recommended_infos RENAME COLUMN example_id TO storyline_id;
ALTER TABLE story_creation_storyline_ratings RENAME COLUMN example_id TO storyline_id;

-- 3) 명시 제약 (V2·V9에서 명명)
-- UNIQUE 제약은 RENAME이 아니라 재생성한다: RENAME COLUMN은 인덱스 릴레이션의 attribute 이름을
-- 갱신하지 않아(pg_attribute 잔존) tbls 같은 카탈로그 조회 도구에 옛 컬럼명이 남기 때문이다.
ALTER TABLE story_creation_storylines
    DROP CONSTRAINT uq_story_creation_examples_order;
ALTER TABLE story_creation_storylines
    ADD CONSTRAINT uq_story_creation_storylines_order UNIQUE (creation_session_id, storyline_order);
ALTER TABLE story_creation_storylines
    RENAME CONSTRAINT ck_story_creation_examples_order TO ck_story_creation_storylines_order;
ALTER TABLE story_creation_storyline_recommended_infos
    DROP CONSTRAINT uq_story_creation_example_recommended_infos_order;
ALTER TABLE story_creation_storyline_recommended_infos
    ADD CONSTRAINT uq_story_creation_storyline_recommended_infos_order UNIQUE (storyline_id, info_order);
ALTER TABLE story_creation_storyline_recommended_infos
    RENAME CONSTRAINT ck_story_creation_example_recommended_infos_order TO ck_story_creation_storyline_recommended_infos_order;
ALTER TABLE story_creation_storyline_ratings
    DROP CONSTRAINT uq_story_creation_example_ratings_example;
ALTER TABLE story_creation_storyline_ratings
    ADD CONSTRAINT uq_story_creation_storyline_ratings_storyline UNIQUE (storyline_id);
ALTER TABLE story_creation_storyline_ratings
    RENAME CONSTRAINT ck_story_creation_example_ratings_rating TO ck_story_creation_storyline_ratings_rating;

-- 4) 자동 명명 PK·FK 제약
ALTER TABLE story_creation_storylines
    RENAME CONSTRAINT story_creation_examples_pkey TO story_creation_storylines_pkey;
ALTER TABLE story_creation_storyline_recommended_infos
    RENAME CONSTRAINT story_creation_example_recommended_infos_pkey TO story_creation_storyline_recommended_infos_pkey;
ALTER TABLE story_creation_storyline_ratings
    RENAME CONSTRAINT story_creation_example_ratings_pkey TO story_creation_storyline_ratings_pkey;
ALTER TABLE story_creation_storylines
    RENAME CONSTRAINT story_creation_examples_creation_session_id_fkey TO story_creation_storylines_creation_session_id_fkey;
ALTER TABLE story_creation_storyline_recommended_infos
    RENAME CONSTRAINT story_creation_example_recommended_infos_example_id_fkey TO story_creation_storyline_recommended_infos_storyline_id_fkey;
ALTER TABLE story_creation_storyline_ratings
    RENAME CONSTRAINT story_creation_example_ratings_example_id_fkey TO story_creation_storyline_ratings_storyline_id_fkey;

-- 5) BIGSERIAL 시퀀스
ALTER SEQUENCE story_creation_examples_id_seq RENAME TO story_creation_storylines_id_seq;
ALTER SEQUENCE story_creation_example_recommended_infos_id_seq RENAME TO story_creation_storyline_recommended_infos_id_seq;
ALTER SEQUENCE story_creation_example_ratings_id_seq RENAME TO story_creation_storyline_ratings_id_seq;
