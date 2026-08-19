-- KNK-848: 스토리라인 생성 회수 재구성(reconcileGeneratedStorylines)이 storyline_request_id로 세션을 조회한다.
-- 조회 성능을 위해 인덱스를 추가한다. 완성 경로 대응 컬럼(creation_request_id)에는 아직 인덱스가 없으므로,
-- 지적 범위인 storyline_request_id만 추가한다. nullable 컬럼이지만 관례상 plain 인덱스로 둔다
-- (idx_story_creation_session_tags_character_id와 동일 스타일 — 레포는 IS NOT NULL partial 관례가 아니다).
CREATE INDEX idx_story_creation_sessions_storyline_request_id
    ON story_creation_sessions (storyline_request_id);
