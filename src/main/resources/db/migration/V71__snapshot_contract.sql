-- KNK-1084: 스냅샷 전환의 **수축(contract) 단계**. 여기서 하는 DROP·NOT NULL·유니크 교체는 하위 호환이
-- 아니라서, 구버전 태스크가 도는 창이 남아 있으면 SELECT가 통째로 실패하거나 INSERT가 막힌다.
--
-- **선행 릴리스가 있다: 스냅샷 쓰기 제거(코드 전용).** 이 마이그레이션이 나가는 릴리스의 롤링 창에서
-- 직전 이미지가 계속 요청을 받는데, 그 이미지의 StoryChat 엔티티에 아래 두 컬럼이 매핑돼 있으면 모든
-- 조회·INSERT에 컬럼명이 실려 즉시 SQL 오류가 난다. 배포 실패 시 이전 digest로 되돌리는 자동 복구도
-- 스키마가 이미 바뀐 뒤라 살아나지 못한다. 그래서 **엔티티에서 두 필드를 걷은 릴리스가 운영에 완전히
-- 배포된 뒤에만** 이 마이그레이션을 낸다. 그 릴리스 이후의 구 태스크는 두 컬럼을 참조하지 않고
-- ending_name_snapshot도 항상 채우므로, 이 릴리스의 롤링 창에서 깨질 자리가 없다.
--
-- 확장 단계(V67·V70)는 v0.3.1로 이미 운영에 나갔고 되돌릴 계획이 없다.
--
-- 두 변경을 한 파일에 담는다. 같은 릴리스에서 함께 나가고, 뒤쪽 도달 집계 정리가 실패하면 앞쪽 DROP도
-- 함께 롤백되는 편이 안전하기 때문이다(PostgreSQL은 DDL도 트랜잭션이다). 반쯤 적용된 상태로 남기지 않는다.

-- ## 1) story_chats 스냅샷 컬럼 DROP (KNK-1059 → KNK-1065)
--
-- 이 둘은 읽기 정본이 stories.last_public_snapshot으로 옮겨간 뒤 아무도 읽지 않는다. 구버전 태스크와
-- 배포 되돌림 때문에 남겨 뒀을 뿐이라 이제 지운다.
ALTER TABLE story_chats DROP COLUMN story_title_snapshot;
ALTER TABLE story_chats DROP COLUMN story_thumbnail_key_snapshot;

-- **이름 스냅샷 셋은 지우지 않는다.** 지금도 읽는 복구 경로다 — FK가 조회 키를 지우거나 비우기 때문에
-- 이 값이 없으면 사용자가 실제로 본 것이 화면에서 사라진다.
--   story_chats.story_prologue_snapshot        시작 설정이 삭제되면 start_setting_id가 NULL이 된다
--   story_chats.reached_ending_name_snapshot   엔딩 교체 시 reached_ending_id가 NULL이 된다
--   story_chats.occurred_main_event_names_snapshot  사건 교체 시 조인 행이 CASCADE로 사라진다
--   story_messages.reached_ending_name_snapshot     위와 같음(턴 단위)

-- ## 2) user_story_ending_reaches 이름 기반 확정 (V70 주석의 후속 4단계)
--
-- 순서가 전부다. 2-1을 건너뛰면 2-3(SET NOT NULL)이 실패하고, 2-2를 건너뛰면 2-4(유니크 생성)가 실패한다.
-- 둘 다 마이그레이션을 실패시켜 앱이 뜨지 않는다.

-- 2-1) 확장 단계 배포 창에 구버전 태스크가 쓴 행은 이름이 NULL이다. ending_id로 라이브 엔딩에서 해소한다.
UPDATE user_story_ending_reaches r
SET ending_name_snapshot = e.name
FROM story_endings e
WHERE r.ending_name_snapshot IS NULL
  AND r.ending_id = e.id;

-- 이름도 id도 없는 행은 어떤 근거로도 이름을 되살릴 수 없다(창 동안 구버전이 쓴 행의 엔딩이 그 뒤 교체돼
-- FK SET NULL로 id까지 비워진 경우). 읽기(StoryService)도 이미 이 행을 버리므로 노출된 적이 없고,
-- 남겨 두면 2-3에서 마이그레이션이 실패해 앱이 뜨지 않는다. 지운다.
DELETE FROM user_story_ending_reaches
WHERE ending_name_snapshot IS NULL;

-- 2-2) 새 유니크로 합쳐질 중복을 최초 도달(id 최소)만 남기고 정리한다. 파괴적이라 확장 단계에서 미뤘다.
--      중복이 생기는 경로는 둘이다: 한 스토리에 시작 설정이 다른 동명 엔딩이 있어 둘 다 도달한 경우와,
--      배포 창의 구버전 행(id만) + 신버전 행(이름만)이 같은 도달을 두 번 남긴 경우(V70 주석 마지막 조합).
DELETE FROM user_story_ending_reaches r
USING user_story_ending_reaches keep
WHERE r.user_id = keep.user_id
  AND r.story_id = keep.story_id
  AND r.ending_name_snapshot = keep.ending_name_snapshot
  AND r.id > keep.id;

-- 2-3) 이름을 정본 식별자로 확정한다. **NOT NULL이 유니크의 전제다** — PostgreSQL의 UNIQUE는 NULL을 서로
--      다른 값으로 취급하므로, 이름이 NULL로 남아 있으면 같은 도달이 몇 번이고 다시 들어간다.
ALTER TABLE user_story_ending_reaches ALTER COLUMN ending_name_snapshot SET NOT NULL;

-- 2-4) 유니크를 이름 기준으로 교체한다. 옛 유니크(user_id, story_id, ending_id)는 엔딩이 교체되면 id가
--      갈려 같은 도달을 못 알아보고, ending_id가 NULL인 행은 아예 막지 못해 더 이상 쓸모가 없다.
ALTER TABLE user_story_ending_reaches
    ADD CONSTRAINT uq_user_story_ending_reaches_name UNIQUE (user_id, story_id, ending_name_snapshot);
ALTER TABLE user_story_ending_reaches DROP CONSTRAINT uq_user_story_ending_reaches;

COMMENT ON COLUMN user_story_ending_reaches.ending_name_snapshot IS
    'KNK-1084: 도달 시점의 엔딩 이름. 집계의 정본 식별자이자 유니크 키다. ending_id는 엔딩 교체로 비워질 수 있는 보조 참조.';
