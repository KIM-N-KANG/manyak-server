-- KNK-739: 계정 연동(로그인된 세션에서 다른 provider를 같은 user_id에 추가)을 받기 위한 유니크 추가.
--
-- 기존 유니크는 (provider, provider_user_id) 하나뿐이라(V16) "한 소셜 계정이 두 회원에게 붙는 것"만 막는다.
-- 연동 API가 생기면 같은 회원에게 **같은 provider의 서로 다른 소셜 계정**이 동시 요청으로 각각 들어올 수 있고,
-- 그 상태가 되면 어느 쪽이 그 회원의 카카오 계정인지 판정할 수 없다(해제 기능도 없어 되돌릴 수단이 없다).
-- 애플리케이션 사전 검사는 경합을 막지 못하므로 DB 제약을 최종 방어선으로 둔다.
--
-- 사전 점검: 아래가 0행이어야 적용된다. 연동 기능이 없던 기간에는 로그인 find-or-create가 (provider, sub)마다
-- 새 User를 만들었으므로 회원당 소셜 행은 1개이며, 위반 행이 있으면 이 마이그레이션이 실패해 드러난다.
--   SELECT user_id, provider, count(*) FROM social_accounts GROUP BY 1, 2 HAVING count(*) > 1;
--
-- idx_social_accounts_user(user_id)는 이 유니크 인덱스의 좌측 프리픽스로 대체되지만, 이번 범위를 넘지 않도록 남겨 둔다.
ALTER TABLE social_accounts
    ADD CONSTRAINT uq_social_accounts_user_provider UNIQUE (user_id, provider);
