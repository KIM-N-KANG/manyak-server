ALTER TABLE push_campaigns ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'MARKETING';
ALTER TABLE push_campaigns ADD CONSTRAINT ck_push_campaigns_kind CHECK (kind IN ('SERVICE', 'MARKETING'));
COMMENT ON COLUMN push_campaigns.kind IS '서비스 알림 또는 광고 알림. 기존 캠페인은 광고 종류';
COMMENT ON COLUMN push_campaigns.started_at IS '선점 시각. 전송 시간 초과 및 회차 종료 소유권 판정 기준';
