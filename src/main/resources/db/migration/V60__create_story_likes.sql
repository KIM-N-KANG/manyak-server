-- KNK-1017: 스토리 좋아요 저장소(스펙 §4-3-1 스토리 좋아요, §4-4).
-- like만 있고 dislike는 없으므로 값 컬럼 없이 행 존재 자체가 좋아요다.
-- 등록·취소 멱등과 likeCount 실 집계·isLiked 판정의 앵커다.

CREATE TABLE story_likes (
    id BIGSERIAL PRIMARY KEY,
    -- 게스트는 좋아요할 수 없다(인증 필수). 회원 행이 사라지면 좋아요도 함께 정리한다.
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 회원당 스토리 1행. 재등록이 중복 행을 만들지 않게 DB가 멱등을 보장한다(경합 포함).
    CONSTRAINT uq_story_likes_user_story UNIQUE (user_id, story_id)
);

-- 위 UNIQUE는 user_id 선두라 스토리별 집계(likeCount)를 태우지 못한다. 별도 인덱스를 둔다.
CREATE INDEX idx_story_likes_story ON story_likes (story_id);
