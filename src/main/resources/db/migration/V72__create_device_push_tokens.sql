-- KNK-1131: 디바이스 푸시 토큰(FCM) 저장. 앱이 발급받은 토큰을 서버에 등록해 두면 발송 경로(KNK-1130·KNK-1115)가
-- user_id로 그 회원의 기기를 찾아 보낸다. 회원 기기만 받는다 — 게스트는 광고성 알림의 동의 주체를 특정할 수
-- 없어 대상이 아니다(스토리 완성 등 정보성 알림의 게스트 확장은 별도 결정).
--
-- token UNIQUE: FCM 토큰은 "이 기기의 이 앱 설치본" 주소라 전역 유일하다. 같은 토큰 재등록은 갱신(멱등)이고,
-- 다른 회원이 같은 토큰을 보내면(한 기기에서 계정 전환) 소유자를 옮긴다. 애플리케이션이 find-then-update로
-- 처리하지만 동시 첫 등록의 중복 행은 이 유니크가 최종 방어선으로 막는다.
-- user_id FK는 users 관례(V16·V24)대로 ON DELETE CASCADE를 두되, 탈퇴는 soft delete라 실제 정리는
-- UserWithdrawalService가 한다(탈퇴 회원 기기로 푸시가 가면 안 된다).
CREATE TABLE device_push_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- FCM 등록 토큰. 지금은 160자 안팎이지만 길이는 FCM 계약이 아니라 여유를 둔다.
    token VARCHAR(512) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 마지막 등록(갱신) 시각. 오래 갱신되지 않은 토큰을 나중에 정리할 때의 근거다.
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_device_push_tokens_token UNIQUE (token),
    CONSTRAINT ck_device_push_tokens_platform CHECK (platform IN ('ANDROID', 'IOS'))
);

-- 발송이 회원의 기기 전부를 찾는 경로(user_id → tokens). 탈퇴 정리도 같은 경로를 쓴다.
CREATE INDEX idx_device_push_tokens_user ON device_push_tokens (user_id);
