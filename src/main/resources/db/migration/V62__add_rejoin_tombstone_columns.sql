-- KNK-1053: 탈퇴 → 재가입 반복으로 1회성 혜택(가입 보상·초대 제출 보상)을 재수령하던 구멍을 막기 위한 스키마.
--
-- 탈퇴가 social_accounts 행을 하드 삭제하면 소셜 신원 ↔ 계정을 잇는 유일한 링크가 사라져, 같은 소셜 계정으로
-- 재가입할 때마다 완전히 새 users 행이 생기고 user_id에 매달린 1회성 표식이 전부 리셋된다.
-- 행을 tombstone(deleted_at 기록)으로 보존하면 (provider, provider_user_id) 유니크가 그대로 남아,
-- 재가입이 **그 행의 재사용**을 DB 차원에서 강제받는다(새 행 insert 경로가 열리면 우회가 다시 생긴다).

-- 소셜 연동의 소프트 삭제 시각. 로그인 조회는 deleted_at IS NULL만 매칭한다(탈퇴 계정 부활 방지).
-- 개인정보 파기는 email을 NULL로 지워 충족한다. provider_user_id는 제공자 없이는 식별 불가한
-- pseudonymous ID이고 재가입 매칭에 필요해 남긴다.
ALTER TABLE social_accounts ADD COLUMN deleted_at TIMESTAMPTZ;

-- 이 회원이 탈퇴 계정의 소셜 신원으로 재가입해 만들어졌음을 표시한다. 가입 보상 스킵 판정에 쓴다.
-- 보상은 "매 로그인마다 멱등 시도"로 유실을 자가 복구하는 구조라, 재가입 여부를 메모리 플래그로만 들면
-- 재가입 첫 로그인이 계정 생성 후 크래시했을 때 다음 로그인이 보상을 지급해 구멍이 다시 열린다. 그래서 영속한다.
ALTER TABLE users ADD COLUMN rejoined_at TIMESTAMPTZ;

-- 1회성 보상 멱등 키의 스코프. NULL이면 "자기 자신"이라 `coalesce(reward_identity_user_id, id)`가 곧 보상 신원이다.
-- 기존 회원·순수 신규 가입은 NULL이므로 멱등 키 문자열(`signup:{id}`·`attendance:{id}:{날짜}`)이 그대로여서
-- 이미 쌓인 원장 행과의 호환이 깨지지 않는다. 재가입 계정만 최초 계정의 id를 들고 있어,
-- user_id를 갈아치우는 재가입이 보상 키를 리셋하지 못한다(출석 250 무제한 반복 차단).
-- 재가입을 반복해도 항상 최초 계정을 가리키도록 체인이 아니라 루트를 복사한다.
--
-- FK를 걸지 않는다: inviter_user_id의 자기참조 FK는 ON DELETE SET NULL(V27)이라 참조 대상이 사라지면 표식이
-- 증발하는데, 이 컬럼은 정반대로 **삭제 안정성**이 존재 이유다. 대상 행이 없어도 값이 그대로 남아야 키가 안정적이다.
ALTER TABLE users ADD COLUMN reward_identity_user_id BIGINT;

-- 탈퇴 직전 계정 상태. 탈퇴는 status를 DELETED로 덮어써 **정지(SUSPENDED)였다는 사실 자체를 지워 버리므로**,
-- 재가입 계정에 제재를 물려주려면 그 값을 따로 남겨야 한다(이게 없으면 정지는 "탈퇴 후 재가입" 한 번으로 무력화된다).
-- 탈퇴 자체는 계속 허용한다(앱 심사 요건 — 정지 회원이라고 계정 삭제를 막을 수 없다).
-- 이 컬럼 도입 전에 탈퇴한 계정은 NULL(승계 판정 불가 → ACTIVE로 재가입)이다.
ALTER TABLE users ADD COLUMN withdrawn_from_status VARCHAR(20);
