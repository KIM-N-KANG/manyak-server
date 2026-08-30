-- KNK-1020: 스토리 신고 저장소(스펙 §4-3-1 스토리 신고, §4-4).
-- 접수 원장이다. 접수 후 처리(노출 제재 등)는 도입 전이며, 등록 시 운영 Slack 알림을 보낸다.

CREATE TABLE story_reports (
    id BIGSERIAL PRIMARY KEY,
    -- 게스트는 신고할 수 없다(인증 필수). 회원 행이 사라지면 신고도 함께 정리한다.
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    -- 신고 사유(SPAM · INAPPROPRIATE · ETC)
    reason VARCHAR(20) NOT NULL,
    -- 자유 서술(선택, 앱 검증 상한 500자)
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 회원당 스토리 1행. 중복 신고가 행을 늘리지 않게 DB가 멱등을 보장한다(경합 포함, 좋아요와 동일 관례).
    CONSTRAINT uq_story_reports_user_story UNIQUE (user_id, story_id)
);

-- 위 UNIQUE는 user_id 선두라 스토리별 신고 집계를 태우지 못한다. 별도 인덱스를 둔다.
CREATE INDEX idx_story_reports_story ON story_reports (story_id);
