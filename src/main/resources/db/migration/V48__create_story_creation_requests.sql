-- KNK-631: 모바일 백그라운드 스토리 생성 복귀(스펙 §4-3-8 Phase 1 계획).
-- 스토리라인 생성·스토리 완성 요청을 클라이언트 생성 request_id로 추적해, 앱 전환으로 연결이 끊겨도
-- 프론트가 복귀 시 진행 상태·결과를 되찾게 한다. 동기 흐름은 유지하고 복구 경로만 추가한다.
-- request_id(UUID)가 외부 노출 조회 키다(순차 PK는 내부용 — IDOR 방지).
CREATE TABLE story_creation_requests (
    id BIGSERIAL PRIMARY KEY,
    request_id UUID NOT NULL,
    -- 소유 주체: 회원은 user_id, 게스트는 device_id(둘 중 하나). 복구 조회 소유 게이트에 쓴다.
    user_id BIGINT,
    device_id VARCHAR(255),
    stage VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    -- 성공 시 원 POST 응답 본문(JSON 직렬화)을 그대로 보관해 복구·멱등 replay에 재사용한다.
    result_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_creation_requests_request_id UNIQUE (request_id),
    CONSTRAINT ck_story_creation_requests_stage
        CHECK (stage IN ('STORYLINE_GENERATION', 'STORY_COMPLETION')),
    CONSTRAINT ck_story_creation_requests_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);
