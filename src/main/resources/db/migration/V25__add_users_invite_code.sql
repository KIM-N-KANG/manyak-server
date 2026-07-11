-- 사용자별 고유 초대 코드를 추가한다(스펙 §4-3-7. GET /users/me/invite로 지연 발급·공유).
-- nullable: 코드는 최초 조회 시 지연 생성하므로, 그 전까지는 NULL이다.
-- UNIQUE: 코드로 초대자를 역해석(POST /auth/login/google의 inviteCode)하므로 전역 유일해야 한다.
--         Postgres UNIQUE는 NULL을 서로 다른 값으로 취급해 미발급(NULL) 사용자 다수를 허용한다.
ALTER TABLE users ADD COLUMN invite_code VARCHAR(16);
ALTER TABLE users ADD CONSTRAINT uq_users_invite_code UNIQUE (invite_code);
