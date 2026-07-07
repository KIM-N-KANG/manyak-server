-- 재생성 응답 버전 이력 보존(스펙 §4-3-9 결정 기록, B11 · KNK-437).
-- AI 응답 재생성은 마지막 턴 ASSISTANT 활성본을 새 출력으로 덮어쓰기 직전에, 직전 활성 출력·선택지를 이 표에 보관한다.
-- 활성본은 story_messages/story_choices에 그대로 남아 상세 조회·SSE completed는 활성본만 노출한다(FE 계약 불변).
-- append-only 이력이며, 이전 출력↔새 출력 쌍이 재생성 원인 분석·AI 품질 평가 데이터의 원천이다.
CREATE TABLE story_message_versions (
    id             BIGSERIAL PRIMARY KEY,
    -- 재생성 대상 ASSISTANT 메시지(story_messages.id). 이 메시지의 과거 출력들이 version_number 순으로 쌓인다.
    message_id     BIGINT NOT NULL,
    -- 이 메시지에서 보관된 순번(1-based). 1 = 최초 출력, 이후 재생성마다 직전 활성본이 다음 번호로 보관된다.
    version_number INT NOT NULL,
    -- 보관 당시의 AI 출력 본문.
    content        TEXT NOT NULL,
    -- 보관 당시의 선택지 텍스트 목록(JSON 배열, choice_order 오름차순). 이력·분석용이라 정규화하지 않고 스냅샷으로 둔다.
    choices        TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_story_message_versions UNIQUE (message_id, version_number)
);

-- 한 메시지의 이력 조회·count(다음 version_number 계산)를 위한 인덱스.
CREATE INDEX idx_story_message_versions_message_id ON story_message_versions (message_id);
