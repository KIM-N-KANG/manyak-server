-- KNK-751: AI 호출에 Langfuse trace 연결 식별자를 헤더로 실어 보내기 위한 스키마.
--
-- AI 서버가 남기는 trace를 "스토리라인 → 컴파일 → 채팅" 여정으로 묶으려면, 여정 전체에 걸쳐 같은 값이 유지되는
-- creation_id가 필요하다. 그 값으로 **스토리라인 단계의 story_creation_requests.request_id**를 쓴다:
-- AI 호출 **전에** 별도 트랜잭션으로 커밋되고(KNK-631), 실패해도 행이 남으며, 같은 requestId 재시도에 동일하다.
-- (story_creation_sessions.id는 AI 성공 이후에야 생기는 순차 PK라 최초 AI 호출 시점에 존재하지 않는다.)

-- 1) 스토리라인 단계의 request_id. 기존 creation_request_id(완성 단계)와 이름으로 구분한다.
ALTER TABLE story_creation_sessions
    ADD COLUMN storyline_request_id UUID;

COMMENT ON COLUMN story_creation_sessions.storyline_request_id IS
    'KNK-751: 스토리라인 생성(STORYLINE_GENERATION) 요청의 request_id. AI trace 여정을 묶는 creation_id로 헤더에 실린다. 이 컬럼 도입 전 세션은 NULL(헤더 생략).';

COMMENT ON COLUMN story_creation_sessions.creation_request_id IS
    'KNK-644: 완성(STORY_CREATED) 시점 요청의 request_id. 회수 재실행 검증용이며 storyline_request_id(스토리라인 단계)와 다른 값이다.';

-- 2) 완성 스토리 → 생성 세션 역조회용. 채팅 생성 시 이 스토리의 creation_id를 1회 해석하는 데 쓴다.
CREATE INDEX idx_story_creation_sessions_story ON story_creation_sessions (story_id);

-- 3) 채팅이 들고 가는 creation_id. 채팅 생성 시 (2)의 인덱스로 1회 해석해 박고, 이후 턴은 조회 없이 그대로 헤더에 싣는다.
--    일반 제작(저작) 스토리는 생성 세션이 없어 NULL이고, 그때는 헤더를 생략한다.
ALTER TABLE story_chats
    ADD COLUMN creation_id UUID;

COMMENT ON COLUMN story_chats.creation_id IS
    'KNK-751: 이 채팅이 시작한 스토리의 간편 제작 creation_id(story_creation_sessions.storyline_request_id). 일반 제작 스토리는 NULL.';
