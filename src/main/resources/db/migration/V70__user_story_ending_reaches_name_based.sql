-- KNK-1065(PR #224 Codex P2): 회원 엔딩 도달 집계를 이름 기반으로 견디게 만든다.
--
-- V41이 만든 fk_user_story_ending_reaches_ending은 **ON DELETE CASCADE**다. 그런데 스토리 수정의
-- endings[] 전체 교체는 엔딩 행을 delete + re-insert하므로, 제작자가 엔딩을 한 번 손보기만 해도
-- **그 스토리로 놀던 모든 회원의 도달 집계가 통째로 삭제된다.** 비공개 전환과 무관하고, 이름을 그대로 둬도
-- 마찬가지다(행이 새로 생기니 id가 바뀐다). 스토리 상세의 reachedEndings가 그대로 비어 버린다.
--
-- 여기에 더해, 도달 판정이 라이브 엔딩 행을 못 찾아 id 없이 기록되는 경우(비공개 상태에서 엔딩이 교체된 뒤
-- 도달)에는 ending_id가 NOT NULL이라 집계에 아예 남길 수 없었다. 채팅 쪽 도달 기록은 이름으로 남는데
-- 집계만 빠져, 제작자가 그 엔딩을 다시 공개해도 영원히 복구되지 않았다.
--
-- 오늘 채팅·메시지에서 쓴 패턴을 그대로 적용한다: **id는 있으면 남기고, 정본 식별자는 이름으로 바꾼다.**

-- 1) 이름 컬럼 추가 후 현재 엔딩 이름으로 백필.
--    기존 행은 ending_id가 NOT NULL이고 FK가 살아 있으므로 이름이 반드시 해소된다(백필 후 NULL이 남지 않는다).
ALTER TABLE user_story_ending_reaches ADD COLUMN ending_name_snapshot VARCHAR(100);

UPDATE user_story_ending_reaches r
SET ending_name_snapshot = e.name
FROM story_endings e
WHERE r.ending_id = e.id;

-- 2) 새 유니크로 합쳐질 중복을 먼저 정리한다.
--    엔딩 이름 유니크는 **시작 설정 스코프**라(EndingValidation.requireDistinctEndingNames) 한 스토리 안에
--    시작 설정이 다른 동명 엔딩이 있을 수 있고, 회원이 둘 다 도달했으면 (user, story, name)이 겹친다.
--    합쳐도 잃는 정보가 없다: 스토리 상세의 reachedEndings는 **이름만 담은 평면 목록**이고 프론트도 이름으로
--    엔딩 목록과 상관하므로(KNK-462), 동명 엔딩 둘은 응답에서 애초에 구분되지 않는다. 오히려 지금은 같은
--    이름이 두 번 실리는데 그 중복이 사라진다.
--    최초 도달을 남긴다(id 최소 = 가장 먼저 기록된 행).
DELETE FROM user_story_ending_reaches r
USING user_story_ending_reaches keep
WHERE r.user_id = keep.user_id
  AND r.story_id = keep.story_id
  AND r.ending_name_snapshot = keep.ending_name_snapshot
  AND r.id > keep.id;

-- 3) 이름을 정본 식별자로 승격한다.
--    **NOT NULL이 핵심이다.** PostgreSQL의 UNIQUE는 NULL을 서로 다른 값으로 취급하므로, 이름이 NULL이면
--    같은 (회원, 스토리)에 같은 도달이 몇 번이고 다시 들어간다. NOT NULL이라야 유니크가 실제로 막는다.
ALTER TABLE user_story_ending_reaches ALTER COLUMN ending_name_snapshot SET NOT NULL;

ALTER TABLE user_story_ending_reaches DROP CONSTRAINT uq_user_story_ending_reaches;
ALTER TABLE user_story_ending_reaches
    ADD CONSTRAINT uq_user_story_ending_reaches_name
        UNIQUE (user_id, story_id, ending_name_snapshot);

-- 4) ending_id는 보조 참조로 낮춘다: 있으면 남기고, 엔딩 행이 사라지면 NULL이 되되 **행은 살아남는다**.
--    CASCADE → SET NULL이 이 마이그레이션의 핵심 한 줄이다.
ALTER TABLE user_story_ending_reaches ALTER COLUMN ending_id DROP NOT NULL;
ALTER TABLE user_story_ending_reaches DROP CONSTRAINT fk_user_story_ending_reaches_ending;
ALTER TABLE user_story_ending_reaches
    ADD CONSTRAINT fk_user_story_ending_reaches_ending
        FOREIGN KEY (ending_id) REFERENCES story_endings (id) ON DELETE SET NULL;

COMMENT ON COLUMN user_story_ending_reaches.ending_name_snapshot IS
    'KNK-1065: 도달 시점의 엔딩 이름. 정본 식별자이며 유니크 키다. ending_id는 엔딩 교체로 비워질 수 있는 보조 참조.';

-- **소급 복구는 못 한다(수용).** 이 마이그레이션 이전에 CASCADE로 이미 삭제된 도달 기록은 행 자체가 없어
-- 되살릴 근거가 없다. 앞으로의 삭제만 막는다.
--
-- 같은 이름의 엔딩이 나중에 다시 생기면 읽기가 자연히 다시 이어진다 — 읽기 경로가 이름으로 상관하기 때문이다
-- (StoryService.resolveReachedEndingNames). ending_id를 되메우지는 않는다: 표시에 쓰이지 않고, 되메우려면
-- 전 회원 집계를 훑어야 하는데 그만한 값이 없다.
