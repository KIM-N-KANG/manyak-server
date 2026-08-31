-- KNK-1056: 크레딧 적립·소모 수치의 런타임 오버라이드 저장소.
--
-- 지금까지 수치는 application.yml의 @Value뿐이라 바꾸려면 릴리스가 필요했고, ECS 태스크 정의 env는 terraform
-- 관리라 되돌리는 데도 앱 배포가 아니라 apply가 필요했다. 출시 이벤트처럼 한시적으로 올렸다 내리는 값은
-- 종료를 사람 기억에 맡기게 되므로(계속 2배 지급), effective_until을 가진 행으로 DB에 둔다.
--
-- 이 테이블이 회계적으로 안전한 이유: 크레딧 원장(credit_transactions)은 append-only이고 각 행이 자기 amount를
-- 기록한다. 수치를 바꿔도 과거 거래는 재계산되지 않고, 멱등 키도 금액과 무관해 중복 방지 판정이 흔들리지 않는다.
--
-- 행은 시드하지 않는다. 빈 테이블 = 전부 yml 기본값이며, 오버라이드가 있을 때만 그 값을 쓴다.
-- 변경 수단은 당분간 운영 SQL이다(관리자 페이지는 권한 개념 도입 후 별도 트랙).
CREATE TABLE credit_policies (
    -- 코드의 CreditPolicyKey enum이 소유하는 키(예: attendance_reward). 모르는 키가 들어와도 무시될 뿐 조회를 깨지 않는다.
    policy_key VARCHAR(50) PRIMARY KEY,
    -- CHECK 상한이 자릿수 오타의 방어선이다. 원장이 append-only라 잘못 지급한 크레딧은 회수할 수 없고,
    -- 운영 SQL로 0 하나 더 붙이는 사고가 곧 비가역 손실이다. 상한 10000은 현행 최대값(2000)의 5배로,
    -- 정상 조정 폭은 다 덮으면서 자릿수 오타는 걸러낸다.
    --
    -- 방어 분담: 여기는 키를 구분하지 않는 **거친** 방어선이고, 키마다 다른 세밀한 판정은 애플리케이션 몫이다
    -- (CreditPolicyKey.minimumAmount — 보상·소모 5종은 1 이상, invite_monthly_cap만 0 허용).
    -- DB는 어떤 키가 0을 허용하는지 모른다. 하한을 0으로 두는 이유가 그것이다.
    amount BIGINT NOT NULL,
    -- NULL이면 상시 적용, 값이 있으면 그 시각 이후 자동으로 yml 기본값으로 되돌아간다(이벤트 종료 장치).
    effective_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_credit_policies_amount CHECK (amount BETWEEN 0 AND 10000)
);
