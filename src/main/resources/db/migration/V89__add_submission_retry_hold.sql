ALTER TABLE story_submissions
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 2),
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN held_at TIMESTAMPTZ,
    ADD COLUMN hold_reason TEXT,
    ADD CONSTRAINT ck_story_submissions_hold CHECK (
        (held_at IS NULL AND hold_reason IS NULL) OR
        (held_at IS NOT NULL AND hold_reason IS NOT NULL AND status = 'PENDING' AND next_attempt_at IS NULL)
    );

DROP INDEX ix_story_submissions_claim;
CREATE INDEX ix_story_submissions_claim ON story_submissions(id, next_attempt_at, dispatched_at)
    WHERE status = 'PENDING' AND held_at IS NULL;

COMMENT ON COLUMN story_submissions.retry_count IS '일시 실패 자동 재시도 예약 횟수(최대 2). 사용자 재제출 시 0';
COMMENT ON COLUMN story_submissions.next_attempt_at IS '다음 자동 재시도 가능 시각. 선점 시 NULL';
COMMENT ON COLUMN story_submissions.held_at IS '자동 재시도 소진 보류 시각. PENDING 유지, 폴러 선점 제외';
COMMENT ON COLUMN story_submissions.hold_reason IS '보류 원인 코드. 사용자 응답에는 노출하지 않음';
