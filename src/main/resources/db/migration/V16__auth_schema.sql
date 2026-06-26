-- 인증 데이터 모델: 사용자(users)와 소셜 로그인 연동(social_accounts)을 추가한다.
-- users.public_id는 외부에 노출하는 추측 불가능한 식별자로, 순차 PK(id) 열거(IDOR)를 막기 위해 API는 이 값만 입출력한다(스토리 KNK-256 선례).
-- social_accounts는 외부에 노출하지 않는 내부 연동 테이블이라 public_id를 두지 않는다.
-- 한 사용자(user)가 여러 소셜 제공자(provider)를 연동할 수 있고, (provider, provider_user_id) 조합이 계정 유일성을 보장한다.
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    nickname VARCHAR(50) NOT NULL,
    profile_image_url TEXT,
    -- 목록·미리보기용 저해상도 썸네일(Base64). 원본은 profile_image_url(외부 스토리지)로 참조한다.
    profile_thumbnail_base64 TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,

    CONSTRAINT uq_users_public_id UNIQUE (public_id),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE social_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    -- 소셜 제공자가 발급한 사용자 식별자.
    provider_user_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    connected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_social_accounts_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT ck_social_accounts_provider CHECK (provider IN ('GOOGLE', 'KAKAO', 'APPLE', 'NAVER'))
);

CREATE INDEX idx_social_accounts_user ON social_accounts(user_id);
