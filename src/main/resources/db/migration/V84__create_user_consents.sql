-- KNK-1334: 약관·개인정보 처리방침 수락과 만 14세 이상 확인을 문서 버전별 이력으로 보존한다.
-- 광고성 수신 동의(users의 현재 상태)와 달리 개정 전 증빙도 필요하므로 append-only로 저장한다.
-- 같은 버전 재제출은 PK 충돌을 무시하고 최초 agreed_at을 유지한다.
-- 탈퇴는 soft delete이며 동의 이력을 보존해야 하므로 FK에 ON DELETE 연쇄를 두지 않는다.
CREATE TABLE user_consents (
    user_id BIGINT NOT NULL REFERENCES users(id),
    doc_type VARCHAR(20) NOT NULL CHECK (doc_type IN ('TERMS', 'PRIVACY', 'AGE14')),
    version VARCHAR(20) NOT NULL,
    agreed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, doc_type, version)
);
