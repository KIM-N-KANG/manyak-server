-- 공지 게시와 알림 캠페인 예약을 분리한다. 기존 공지 참조와 알림 요청 시각은 보존한다.
ALTER TABLE push_campaigns DROP CONSTRAINT uq_push_campaigns_notice;
ALTER TABLE push_campaigns ADD COLUMN failure_reason varchar(40);
ALTER TABLE push_campaigns ADD CONSTRAINT ck_push_campaigns_failure_reason
    CHECK (failure_reason IN ('NOTICE_NOT_PUBLISHED'));
-- notification_title, notification_body는 구버전 태스크 호환을 위해 다음 릴리스까지 보존한다.
COMMENT ON COLUMN push_campaigns.notice_id IS '캠페인에 연결한 공지. 같은 공지에 여러 캠페인 허용';
COMMENT ON COLUMN push_campaigns.failure_reason IS '발송 없이 실패한 사유. 재실행하면 NULL';
