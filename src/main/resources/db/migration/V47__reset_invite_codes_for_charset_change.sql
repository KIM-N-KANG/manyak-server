-- 초대 방식 개편(KNK-567, 스펙 §4-3-7): 초대 코드 문자 집합을 혼동 문자(O·0, I·1·L) 제외
-- 대문자+숫자로 바꾸면서, 구 집합(영대소문자+숫자)으로 발급된 기존 코드를 전량 회수한다.
-- NULL로 되돌리면 다음 GET /users/me/invite가 새 집합으로 지연 재발급한다.
-- 링크 방식을 실사용한 사용자가 없어 유포된 코드가 없고, 재발급 피해도 없다(스펙 결정 기록 2026-07-11).
UPDATE users
SET invite_code = NULL
WHERE invite_code IS NOT NULL;
