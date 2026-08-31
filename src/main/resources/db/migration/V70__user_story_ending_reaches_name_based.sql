-- KNK-1065(PR #224 Codex P2): 회원 엔딩 도달 집계를 이름 기반으로 견디게 만든다 — **확장(expand) 단계**.
--
-- V41이 만든 fk_user_story_ending_reaches_ending은 ON DELETE CASCADE다. 그런데 스토리 수정의 endings[]
-- 전체 교체는 엔딩 행을 delete + re-insert하므로, **제작자가 엔딩을 한 번 손보기만 해도 그 스토리로 놀던
-- 모든 회원의 도달 집계가 통째로 삭제된다.** 이름을 그대로 둬도 마찬가지고(행이 새로 생기니 id가 바뀐다)
-- 비공개와도 무관하다. 여기에 더해, 도달 판정이 라이브 엔딩 행을 못 찾아 id 없이 기록되는 경우에는
-- ending_id가 NOT NULL이라 집계에 아예 남길 수 없었다.
--
-- **이 마이그레이션은 완화·추가만 한다.** 롤링 배포 중 구버전 태스크가 계속 INSERT하기 때문이다.
-- 조이는 단계(중복 정리·NOT NULL·옛 유니크 제거)는 릴리스가 끝난 뒤 별도 티켓에서 한다 — 맨 아래 참고.

-- 1) 이름 컬럼을 **nullable로** 추가하고 현재 엔딩 이름으로 백필한다.
--    **NOT NULL로 만들지 않는다**: 구버전 태스크는 이 컬럼을 모르고 INSERT하므로 NOT NULL이면 그 창 동안
--    도달 저장이 통째로 실패한다(컬럼 추가는 하위 호환이지만 NOT NULL 제약은 아니다). 오늘 story_chats
--    DROP 건에서 쓴 expand/contract를 정작 새 컬럼에 적용하지 않았던 자리다.
--    기존 행은 ending_id가 NOT NULL이고 FK가 살아 있으므로 이름이 반드시 해소된다.
ALTER TABLE user_story_ending_reaches ADD COLUMN ending_name_snapshot VARCHAR(100);

UPDATE user_story_ending_reaches r
SET ending_name_snapshot = e.name
FROM story_endings e
WHERE r.ending_id = e.id;

-- 2) ending_id를 보조 참조로 낮춘다: 있으면 남기고, 엔딩 행이 사라지면 NULL이 되되 **행은 살아남는다**.
--    CASCADE → SET NULL이 이 마이그레이션의 핵심이며, 둘 다 구버전에 하위 호환이다 —
--    구버전은 항상 ending_id를 채우므로 DROP NOT NULL에 영향받지 않고, FK 삭제 규칙은 앱이 신경 쓰지 않는다.
ALTER TABLE user_story_ending_reaches ALTER COLUMN ending_id DROP NOT NULL;
ALTER TABLE user_story_ending_reaches DROP CONSTRAINT fk_user_story_ending_reaches_ending;
ALTER TABLE user_story_ending_reaches
    ADD CONSTRAINT fk_user_story_ending_reaches_ending
        FOREIGN KEY (ending_id) REFERENCES story_endings (id) ON DELETE SET NULL;

COMMENT ON COLUMN user_story_ending_reaches.ending_name_snapshot IS
    'KNK-1065: 도달 시점의 엔딩 이름. 다음 릴리스에서 NOT NULL + 유니크 키로 승격한다. ending_id는 엔딩 교체로 비워질 수 있는 보조 참조.';

-- 3) 유니크는 **옛 것(user_id, story_id, ending_id)을 그대로 둔다.** 새 이름 유니크는 지금 만들지 않는다.
--
--    이름 유니크를 함께 두면 각자 자기 스타일 행을 막아 창의 노출이 줄어드는 건 맞다. 그런데 **지금은 만들 수
--    없다**: 엔딩 이름 유니크가 시작 설정 스코프라(EndingValidation.requireDistinctEndingNames) 한 스토리 안에
--    시작 설정이 다른 동명 엔딩이 있을 수 있고, 회원이 둘 다 도달했으면 백필 직후 (user, story, name)이 겹친다.
--    그 상태에서 UNIQUE 생성은 **마이그레이션 자체를 실패시켜 앱이 뜨지 않는다.** 먼저 중복을 정리해야 하는데
--    그 정리는 행을 지우는 파괴적 작업이라 창이 닫힌 뒤(= 구버전이 더 이상 쓰지 않을 때) 하는 게 맞다.
--    확장 단계에 실패할 수 있는 문장을 넣지 않는다는 원칙을 지킨다.
--
--    창 동안 무엇이 뚫리는가:
--      - 구버전 행끼리(id 있음)            → 옛 유니크가 막는다.
--      - 신버전 행 중 id가 있는 것          → 옛 유니크가 막는다(구버전 행과의 충돌도 포함).
--      - 신버전 행 중 id가 NULL인 것끼리    → DB 제약 없음. 앱의 이름 기준 가드(EndingReachRecorder)가 막고,
--                                            동시 삽입 경합만 새는데 그건 원래 유니크가 잡던 몫이다.
--      - 구버전(id=5, name=NULL) + 신버전(id=NULL, name='해피')
--                                          → **어떤 제약으로도 못 막는다.** 구버전 행이 이름을 안 들고 있어
--                                            같은 도달임을 알아볼 근거가 DB에 없기 때문이다(이름 유니크를
--                                            함께 뒀어도 마찬가지라, 이 구멍은 선택과 무관하다).
--    실제 노출: 같은 회원이 **다른 채팅으로** 같은 엔딩에 두 번 도달해야 하고(채팅당 도달은 1회 가드가 있다),
--    그중 한 번은 구버전이, 다른 한 번은 신버전이 라이브 엔딩을 못 찾은 상태로 처리해야 한다. 좁다.
--    결과도 보이지 않는다 — 읽기(StoryService.resolveReachedEndingNames)가 이름으로 distinct하므로 화면에는
--    한 번만 실리고, 아래 후속 단계의 중복 정리가 그 행을 치운다.

-- ## 후속(contract) 단계 — 릴리스가 끝난 뒤 별도 티켓
--   1. 이름이 NULL인 행을 ending_id로 다시 백필한다(창 동안 구버전이 쓴 행).
--   2. (user_id, story_id, ending_name_snapshot) 중복을 최초 도달(id 최소)만 남기고 정리한다.
--   3. ALTER COLUMN ending_name_snapshot SET NOT NULL.
--      **NOT NULL이 유니크의 전제다** — PostgreSQL의 UNIQUE는 NULL을 서로 다른 값으로 취급하므로,
--      이름이 NULL로 남아 있으면 같은 도달이 몇 번이고 다시 들어간다.
--   4. UNIQUE (user_id, story_id, ending_name_snapshot) 추가 후 uq_user_story_ending_reaches 제거.
--
-- **소급 복구는 못 한다(수용).** 이 마이그레이션 이전에 CASCADE로 이미 삭제된 도달 기록은 행 자체가 없어
-- 되살릴 근거가 없다. 앞으로의 삭제만 막는다.
--
-- 같은 이름의 엔딩이 나중에 다시 생기면 읽기가 자연히 다시 이어진다 — 읽기 경로가 이름으로 상관하기 때문이다
-- (StoryService.resolveReachedEndingNames). ending_id를 되메우지는 않는다: 표시에 쓰이지 않고, 되메우려면
-- 전 회원 집계를 훑어야 하는데 그만한 값이 없다.
