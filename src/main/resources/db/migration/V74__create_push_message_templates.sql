-- KNK-1116: 푸시 알림 문구의 런타임 오버라이드 저장소.
--
-- 알림 문구는 마케팅 문구라 릴리스 없이 바꿔야 한다(이벤트 기간 문구, 표현 실험). credit_policies(V66)와 같은
-- 구조·같은 읽기 규칙이다: 유효한 행이 있으면 그 값, 없으면 application.yml의 기본 문구.
--
-- 행은 시드하지 않는다. 빈 테이블 = 전부 yml 기본 문구다. 변경 수단은 당분간 운영 SQL이며 관리자 API는 없다.
-- 동의·발송 이력이 아니라 **문구**만 담는다(수신 동의는 users의 세 컬럼 — V73).
CREATE TABLE push_message_templates (
    id BIGSERIAL PRIMARY KEY,
    -- 코드의 PushTemplateKey enum이 소유하는 키(예: attendance_reminder). 모르는 키는 조회에서 무시된다.
    -- credit_policies와 달리 PK가 아니다 — 같은 키의 기간별 행을 미리 넣어 둘 수 있어야 한다.
    template_key VARCHAR(64) NOT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(300) NOT NULL,
    -- 적용 구간 [effective_from, effective_until). until이 NULL이면 상시 적용이다.
    -- 유효한 행이 여럿이면 effective_from이 가장 최신인 하나를 쓴다(예약 교체가 자연히 동작한다).
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 적재는 키별 조회가 아니라 전량 스캔이지만(행이 몇 개뿐), 키가 늘면 그때도 이 인덱스가 그대로 쓰인다.
CREATE INDEX idx_push_message_templates_key ON push_message_templates (template_key);
