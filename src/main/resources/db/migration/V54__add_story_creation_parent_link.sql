-- KNK-755: 스토리라인 재생성 체인(parent_creation_id)의 쓰기 검증 결과를 저장하는 스키마.
--
-- 지금까지 X-Manyak-Parent-Creation-Id는 순수 pass-through라, 존재하지 않거나 남의 여정을 가리키는 값도 그대로 AI로 나갔다.
-- AI 파이프라인이 체인을 신뢰하고 journey를 조립하려면 백엔드가 부모 관계를 검증해 **통과한 값만** 내보내야 한다.
--
-- 체인의 정본은 story_creation_sessions가 아니라 이 테이블이다: 세션은 AI 성공 이후에만 생겨서 실패한 재생성 시도가
-- 체인에서 빠지지만, story_creation_requests 행은 AI 호출 **전에** 커밋된다(KNK-631).
--
-- 순환은 구조적으로 불가능하다 — 부모는 쓰기 시점에 이미 존재하는 과거 행만 가리킬 수 있다. 자기참조만 별도로 막는다.

-- 1) 검증을 통과한 부모(자기참조). FK 제약은 걸지 않는다 — 이 테이블의 다른 request_id 참조 컬럼들과 같은 패턴이다.
ALTER TABLE story_creation_requests
    ADD COLUMN parent_request_id UUID;

COMMENT ON COLUMN story_creation_requests.parent_request_id IS
    'KNK-755: 검증(존재·자기참조 아님·소유 연속성)을 통과한 직전 생성의 request_id. 이 값만 X-Manyak-Parent-Creation-Id 헤더로 나간다. 최초 생성이거나 검증 실패면 NULL.';

-- 2) 프론트가 실제로 보낸 원값. 검증 실패해도 지우지 않는다 — parent_request_id만 NULL이면
--    "애초에 부모가 없는 최초 생성"과 "재생성인데 연결 실패한 행"을 구분할 수 없어 후자가 최초 생성으로 오판된다.
ALTER TABLE story_creation_requests
    ADD COLUMN attempted_parent_creation_id UUID;

COMMENT ON COLUMN story_creation_requests.attempted_parent_creation_id IS
    'KNK-755: 프론트가 보낸 parentCreationId 원값. 검증 통과 여부와 무관하게 그대로 보존한다(안 보냈으면 NULL). 최초 생성과 연결 실패를 구분하는 근거.';

-- 3) 검증 실패 사유. 400으로 거부하지 않으므로(관측이 비즈니스를 막지 않는다) 실패는 이 컬럼으로만 드러난다.
ALTER TABLE story_creation_requests
    ADD COLUMN parent_link_error VARCHAR(32);

COMMENT ON COLUMN story_creation_requests.parent_link_error IS
    'KNK-755: 부모 링크 검증 실패 사유(NOT_FOUND·OWNER_MISMATCH·SELF_REFERENCE). 검증 성공이거나 애초에 부모를 안 보냈으면 NULL.';
