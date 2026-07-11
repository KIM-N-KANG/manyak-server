-- users.inviter_user_id에 자기참조 FK를 건다(스펙 §4-3-7, KNK-393). V26에서 추가한 컬럼을 무결성으로 보강한다.
--
-- 없으면 초대자가 물리 삭제될 때 이 컬럼이 사라진 사용자를 가리키는 dangling 참조로 남는다. 로그인은
-- inviter_user_id를 권위값으로 rewardInvitePair를 호출하는데, credit_wallets·credit_transactions의
-- user_id → users(id) FK 때문에 사라진 초대자에게 적립을 시도하면 FK 위반으로 로그인이 500이 된다.
--
-- ON DELETE SET NULL: 초대자가 삭제되면 피초대자의 관계를 NULL로 비워, 다음 로그인이 초대 보상을 자연히
-- 건너뛰게 한다(초대자 삭제가 피초대자 계정·데이터에 영향을 주지 않는다). 다른 users 참조 FK(credit_*, auth)와
-- 달리 CASCADE가 아닌 SET NULL을 쓰는 이유는, 초대자 삭제로 피초대자까지 삭제되면 안 되기 때문이다.
ALTER TABLE users
    ADD CONSTRAINT fk_users_inviter_user_id
    FOREIGN KEY (inviter_user_id) REFERENCES users (id) ON DELETE SET NULL;
