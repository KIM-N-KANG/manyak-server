-- KNK-1147(정책 KNK-1146): 닉네임을 정규화 기준으로 유일하게 만든다.
--
-- 정규화 키는 `replace(lower(nickname), ' ', '')`다 — 대소문자와 공백만 다른 닉네임("Story Teller" vs
-- "storyteller")을 같은 것으로 본다. 사칭·혼동을 막는 게 목적이고, 표시값은 사용자가 고른 그대로 둔다.
--
-- 정규식(regexp_replace) 대신 replace를 쓰는 이유: 앱 검증이 허용 문자를 한글 완성형·영문·숫자·**스페이스**로
-- 제한하므로 두 식이 동치이고, replace는 H2에서도 같은 결과라 **인덱스식과 조회식을 하나로 통일**할 수 있다.
-- 생성 컬럼(GENERATED ALWAYS AS)을 두지 않는 이유도 같다 — 함수 인덱스만으로 끝나고, 컬럼을 두면 테스트
-- 프로파일(H2 + ddl-auto)이 그것을 일반 컬럼으로 만들어 NOT NULL 위반이 난다.
--
-- 인덱스를 만들기 전에 기존 중복을 풀어 준다. 랜덤 발급은 조합이 1,600가지뿐이라 이미 겹친 행이 있을 수 있다.
-- 그룹의 첫 행(id 최소)은 그대로 두고 나머지에만 '#id'를 붙인다 — id가 유일하므로 접미 결과도 반드시 유일해
-- 재귀 보정이 필요 없다. VARCHAR(50)을 넘지 않도록 접미 길이만큼 앞을 잘라 붙인다.
UPDATE users u
SET nickname = left(u.nickname, 50 - length('#' || u.id)) || '#' || u.id
WHERE EXISTS (
    SELECT 1 FROM users other
    WHERE other.id < u.id
      AND replace(lower(other.nickname), ' ', '') = replace(lower(u.nickname), ' ', '')
);

CREATE UNIQUE INDEX uq_users_nickname_key ON users ((replace(lower(nickname), ' ', '')));
