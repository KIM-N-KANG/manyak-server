-- KNK-1132(정책 KNK-1129): 알림 수신 동의. 푸시는 종류에 따라 동의 요건이 다르다(정보통신망법 제50조).
--
-- 서비스 알림(스토리 완성·검수 완료)은 사용자가 유발한 작업의 결과 통지라 사전 동의가 필요 없어 기본 켜짐
-- 옵트아웃이고, 광고 알림(프로모션·출석 리마인드)은 사전 동의가 필수인 옵트인이다.
--
-- 광고성 두 값을 boolean이 아니라 **동의 시각**으로 두는 이유: 값 자체가 동의 증빙이다("언제부터 동의했는가").
-- 철회는 NULL로 지운다. 재동의는 최초 시각을 유지한다(증빙은 최초 동의 시점이라 매 요청마다 밀리면 안 된다).
-- 동의·철회 이력 테이블은 두지 않는다 — 현재 상태와 최초 동의 시각으로 충분하다(KNK-1129 결정).
--
-- 기존 행은 DEFAULT로 채워진다: 서비스 알림은 켜진 상태로, 광고성 둘은 미동의(NULL)로 시작한다.
ALTER TABLE users
    ADD COLUMN service_push_enabled BOOLEAN NOT NULL DEFAULT true,
    -- 광고 알림 동의 시각. NULL이면 미동의·철회다.
    ADD COLUMN marketing_push_agreed_at TIMESTAMPTZ,
    -- 야간(21~08시 KST) 광고 알림 동의 시각. 광고 동의와 별개로 받아야 한다(단독으로 켤 수 없다).
    ADD COLUMN marketing_push_night_agreed_at TIMESTAMPTZ;
