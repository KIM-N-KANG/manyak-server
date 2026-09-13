-- V75 인덱스가 탈퇴 행까지 포함하여 다음 회원 탈퇴가 닉네임 유니크 위반으로 실패했다.
-- 유일성은 살아 있는 회원 간 사칭 방지가 목적이므로 탈퇴 회원은 대상에서 제외한다.
DROP INDEX uq_users_nickname_key;

-- V81이 공백을 지워 기존 탈퇴 행의 닉네임이 탈퇴한사용자가 되었다.
-- 코드의 익명화 상수와 표기를 맞추기 위해 공백을 복원한다.
-- 이 UPDATE로 생기는 탈퇴 행 간 중복은 아래 부분 인덱스에서 제외되므로 허용된다.
UPDATE users SET nickname = '탈퇴한 사용자'
WHERE deleted_at IS NOT NULL AND replace(lower(nickname), ' ', '') = '탈퇴한사용자';

CREATE UNIQUE INDEX uq_users_nickname_key
    ON users ((replace(lower(nickname), ' ', '')))
    WHERE deleted_at IS NULL;
