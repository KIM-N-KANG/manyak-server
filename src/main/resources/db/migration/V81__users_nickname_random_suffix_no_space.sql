-- KNK-1274: 기존 닉네임의 공백과 V75가 노출한 순차 PK 접미를 제거한다.
-- #은 닉네임 허용 문자 밖이라 프로필 수정 검증에도 실패하므로 랜덤 숫자 4자리로 교체한다.
-- 정규화 유일 인덱스는 이미 공백을 무시하므로 공백 제거로 새 충돌이 생기지 않는다.
DO $$
DECLARE
    target RECORD;
    base_name TEXT;
    candidate TEXT;
    attempt INTEGER;
BEGIN
    UPDATE users SET nickname = replace(nickname, ' ', '') WHERE nickname LIKE '% %';

    FOR target IN SELECT id, nickname FROM users WHERE nickname ~ ('#' || id || '$') ORDER BY id
    LOOP
        base_name := left(target.nickname, length(target.nickname) - length('#' || target.id));
        FOR attempt IN 1..50
        LOOP
            -- 정수 캐스팅의 반올림으로 10000이 나오지 않도록 먼저 내린다.
            candidate := left(base_name, 46) || lpad(floor(random() * 10000)::int::text, 4, '0');
            IF NOT EXISTS (
                SELECT 1 FROM users
                WHERE id <> target.id
                  AND replace(lower(nickname), ' ', '') = replace(lower(candidate), ' ', '')
            ) THEN
                UPDATE users SET nickname = candidate WHERE id = target.id;
                EXIT;
            END IF;
            IF attempt = 50 THEN
                RAISE EXCEPTION 'KNK-1274: nickname suffix allocation exhausted after 50 attempts';
            END IF;
        END LOOP;
    END LOOP;
END $$;
