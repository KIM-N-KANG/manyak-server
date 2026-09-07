-- KNK-1117: 프로모션 푸시의 예약과 이력.
--
-- 트리거는 운영자 SQL이다. 관리자 API·화면은 두지 않는다 — 팀이 전원 개발자이고 서버에 관리자 역할 체계가
-- 없다(로어북 시드·정지 처리와 같은 관례). 행을 넣으면 예약이고, 집기 전에 status를 CANCELED로 바꾸면 취소다.
--
-- 문구는 이 행이 직접 갖는다. push_message_templates(V74)는 같은 문구를 반복해서 쓰는 알림(출석 리마인드)의
-- 오버라이드 저장소라, 회차마다 문구가 다른 캠페인과는 성격이 다르다.
--
-- 회원별 발송 기록 테이블은 두지 않는다. 이력은 이 행의 카운트가 전부다(스펙 §4-3-5 프로모션 푸시).
CREATE TABLE push_campaigns (
    id BIGSERIAL PRIMARY KEY,
    -- 푸시 페이로드의 campaignId로 나가는 외부 식별자. 순차 PK는 노출하지 않는다.
    public_id UUID NOT NULL UNIQUE,
    -- (광고) 접두는 저장하지 않는다. 발송 시점에 서버가 붙인다 — 운영 SQL로 문구를 바꾸다 빠뜨리면
    -- 표기 없는 광고가 나가고 그건 되돌릴 수 없다.
    title VARCHAR(100) NOT NULL,
    body VARCHAR(300) NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    -- SCHEDULED: 예약됨(스케줄러가 집을 대상) / SENDING: 한 인스턴스가 선점해 발송 중
    -- SENT: 회차 완료 / CANCELED: 운영자 취소 / FAILED: 회차가 예외로 끝남(예: 대상 조회 실패)
    status VARCHAR(20) NOT NULL CONSTRAINT ck_push_campaigns_status
        CHECK (status IN ('SCHEDULED', 'SENDING', 'SENT', 'CANCELED', 'FAILED')),
    -- 회차 뒤에 채워지는 이력. 조회 대상 수, 실제 발송 수, 발송 직전 재확인에서 걸러진 수.
    target_count INT,
    sent_count INT,
    skipped_count INT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 스케줄러가 1분마다 던지는 "도래한 SCHEDULED" 조회를 그대로 덮는다.
CREATE INDEX idx_push_campaigns_status_scheduled_at ON push_campaigns (status, scheduled_at);

-- 운영 예약 예시(KST 20:00 발송):
--   INSERT INTO push_campaigns (public_id, title, body, scheduled_at, status)
--   VALUES (gen_random_uuid(), '새 오리지널 스토리가 나왔어요',
--           '이번 주 신작 5편을 지금 만나보세요.', '2026-09-10 20:00:00+09', 'SCHEDULED');
--
-- 취소(집기 전에만 유효하다 — SENDING으로 넘어간 뒤에는 회차가 이미 돌고 있다):
--   UPDATE push_campaigns SET status = 'CANCELED' WHERE public_id = '...' AND status = 'SCHEDULED';
