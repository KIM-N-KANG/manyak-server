-- KNK-1349: 게스트 개인정보 수집 및 이용 동의를 디바이스별 버전 이력으로 보존한다.
-- 체험 한도와 같은 DeviceIdHasher의 28자 해시를 저장하며 원본 디바이스 ID는 저장하지 않는다.
-- 같은 버전 재제출은 PK 충돌을 무시하고 최초 agreed_at을 유지한다(append-only).
-- 로그인 시 회원 동의를 다시 받으므로 이관하지 않으며 회원 탈퇴와 무관하게 보존한다.
CREATE TABLE guest_consents (
    device_id_hash VARCHAR(64) NOT NULL,
    doc_type VARCHAR(20) NOT NULL CHECK (doc_type IN ('GUEST_PRIVACY')),
    version VARCHAR(20) NOT NULL,
    agreed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id_hash, doc_type, version)
);
