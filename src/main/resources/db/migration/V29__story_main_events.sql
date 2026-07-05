-- 주요 사건(스토리 소유 1:N) 스키마(스펙 §4-3-10, KNK-418).
-- 일반 제작·수정 폼이 저장하고, 채팅 런타임이 목표 사건 선정·진행·완결 판정 입력으로 쓰는 저장 초안 구조를 확정한다.
-- 간편 제작도 컴파일 산출물의 주요 사건을 같은 테이블에 저장해, 제작 방식과 무관하게 동일 런타임이 동작한다.
--
-- 스토리당 최대 10개 제약은 count 상한이라 DB가 아니라 앱에서 강제한다(교체 저장 시 검증).
-- (story_id, sort_order) 유니크로 스토리 내 표시 순서를 유일하게 보장한다(교체 시 0..n-1로 부여).
CREATE TABLE story_main_events (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories (id) ON DELETE CASCADE,
    -- AI 요청·거쳐온 사건 기록의 식별자로 쓰는 사건 이름.
    name VARCHAR(100) NOT NULL,
    description TEXT NOT NULL,
    -- 목표 사건 선정·완결 판정의 관련성 근거 문장(런타임 의미는 §4-3-10).
    key_sentence TEXT NOT NULL,
    sort_order SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_main_events_order UNIQUE (story_id, sort_order)
);

CREATE INDEX idx_story_main_events_story ON story_main_events (story_id);
