-- 간편 제작 예시 스토리라인(story_creation_examples)에 대한 좋아요/나빠요 평가를 저장한다.
-- 스토리라인은 생성한 단 한 명만 보고 평가하므로(공유되지 않음) 평가 주체를 식별하지 않고
-- 대상(example)당 1개의 평가만 둔다. 취소는 행을 물리 삭제한다.
CREATE TABLE story_creation_example_ratings (
    id BIGSERIAL PRIMARY KEY,
    example_id BIGINT NOT NULL REFERENCES story_creation_examples(id) ON DELETE CASCADE,
    rating VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_creation_example_ratings_example
        UNIQUE (example_id),
    CONSTRAINT ck_story_creation_example_ratings_rating
        CHECK (rating IN ('GOOD', 'BAD'))
);
