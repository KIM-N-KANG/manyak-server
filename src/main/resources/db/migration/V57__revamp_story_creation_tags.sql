-- KNK-847: 제작 태그 마스터 개편(61 → 40). 팀 확정 목록(2026-08-18).
--
-- 스토리 제작 입력이 인물 단위(KNK-844)로 바뀌며 분류가 어긋났다. GENRE에 섞인 주인공 상태 태그를
-- PROTAGONIST로 옮기고, 개명·신규·비활성으로 목록을 15(GENRE)/15(PROTAGONIST)/10(SUPPORTING)으로 정리한다.
--
-- 설계 메모
-- * 정규화 키(normalized_name) = trim → 내부 공백 전부 제거 → lowercase. StoryCreationTag.normalize와 동일한 규칙이어야 한다.
--   SQL로는 lower(regexp_replace(name, '[[:space:]]', '', 'g')). 아래 개명·신규는 그 결과를 직접 박는다.
-- * 유니크 제약은 (tag_source, tag_type, normalized_name)이며 is_active를 보지 않는다. 개명이 기존 행(비활성 포함)과
--   정규화 키가 겹치면 실패하므로, 겹치는 비활성 행은 개명 전에 병합한다(학원물→학원 케이스, 아래 0단계).
-- * 비활성화는 DELETE가 아니다. 기존 세션이 story_creation_session_tags.tag_id로 참조 중이라 이력이 깨진다.
-- * 개명·카테고리 이동은 그 자리 UPDATE라 과거 제작 이력에 소급 적용된다(2026-08-18 결정).
-- * normalized_name은 V51에서 NOT NULL이 됐고 DB 기본값이 없다. INSERT는 normalized_name을 반드시 함께 넣는다.

-- 0) 개명 충돌 병합: V2 시드의 비활성 GENRE '학원'(normalized_name '학원')은 아래 '학원물'→'학원' 개명과 정규화 키가 겹친다.
--    비활성 '학원' 행을 개명 대상 '학원물' 행으로 병합(참조 재지정 후 삭제)해 충돌을 없앤다. 두 행이 동시에 선택
--    가능했던 적이 없어 (session, tag) 충돌은 실무상 없지만, 있어도 안전하도록 중복 참조는 먼저 지운다(V51과 같은 패턴).
--    안전 가드: stale는 **비활성('학원')**일 때만, 병합·삭제는 **target('학원물')이 존재**할 때만 수행한다.
--    - '학원'이 활성인 편차 상태면 병합하지 않고 그대로 둔다(활성 태그 ID 안정성·캐시된 선택 보호).
--    - target 부재(이미 개명됨 등)면 stale를 삭제하지 않는다(개명이 복구 못 하는 silent drop 방지).
DELETE FROM story_creation_session_tags s
USING story_creation_tags stale, story_creation_tags target
WHERE stale.tag_source = 'PREDEFINED' AND stale.tag_type = 'GENRE' AND stale.name = '학원'
  AND stale.normalized_name = '학원' AND stale.is_active = FALSE
  AND target.tag_source = 'PREDEFINED' AND target.tag_type = 'GENRE' AND target.name = '학원물'
  AND s.tag_id = stale.id
  AND EXISTS (
      -- 실제 유니크 키는 (creation_session_id, character_id, tag_id)다. character_id까지 같은 경우만 중복이므로
      -- IS NOT DISTINCT FROM(NULL=NULL 포함)으로 그 범위를 좁혀 과잉 삭제를 막는다.
      SELECT 1 FROM story_creation_session_tags e
      WHERE e.creation_session_id = s.creation_session_id
        AND e.tag_id = target.id
        AND e.character_id IS NOT DISTINCT FROM s.character_id
  );

UPDATE story_creation_session_tags s
SET tag_id = target.id
FROM story_creation_tags stale, story_creation_tags target
WHERE stale.tag_source = 'PREDEFINED' AND stale.tag_type = 'GENRE' AND stale.name = '학원'
  AND stale.normalized_name = '학원' AND stale.is_active = FALSE
  AND target.tag_source = 'PREDEFINED' AND target.tag_type = 'GENRE' AND target.name = '학원물'
  AND s.tag_id = stale.id;

DELETE FROM image_preset_genres g
USING story_creation_tags stale, story_creation_tags target
WHERE stale.tag_source = 'PREDEFINED' AND stale.tag_type = 'GENRE' AND stale.name = '학원'
  AND stale.normalized_name = '학원' AND stale.is_active = FALSE
  AND target.tag_source = 'PREDEFINED' AND target.tag_type = 'GENRE' AND target.name = '학원물'
  AND g.tag_id = stale.id
  AND EXISTS (
      SELECT 1 FROM image_preset_genres e
      WHERE e.image_preset_id = g.image_preset_id AND e.tag_id = target.id
  );

UPDATE image_preset_genres g
SET tag_id = target.id
FROM story_creation_tags stale, story_creation_tags target
WHERE stale.tag_source = 'PREDEFINED' AND stale.tag_type = 'GENRE' AND stale.name = '학원'
  AND stale.normalized_name = '학원' AND stale.is_active = FALSE
  AND target.tag_source = 'PREDEFINED' AND target.tag_type = 'GENRE' AND target.name = '학원물'
  AND g.tag_id = stale.id;

-- 삭제도 target 존재 + stale 비활성 조건으로 묶는다. 조건 미충족이면 '학원' 행을 보존한다(B1).
DELETE FROM story_creation_tags stale
USING story_creation_tags target
WHERE stale.tag_source = 'PREDEFINED' AND stale.tag_type = 'GENRE' AND stale.name = '학원'
  AND stale.normalized_name = '학원' AND stale.is_active = FALSE
  AND target.tag_source = 'PREDEFINED' AND target.tag_type = 'GENRE' AND target.name = '학원물';

-- 1) 비활성화 (32). is_active = FALSE (삭제 금지).
UPDATE story_creation_tags
SET is_active = FALSE, updated_at = now()
WHERE tag_source = 'PREDEFINED'
  AND (tag_type, name) IN (
      ('GENRE', '계약 결혼'), ('GENRE', '던전'), ('GENRE', '악역물'), ('GENRE', '육아물'),
      ('GENRE', '복수극'), ('GENRE', '성장물'),
      ('PROTAGONIST', '냉정한'), ('PROTAGONIST', '복수형'), ('PROTAGONIST', '헌신적인'), ('PROTAGONIST', '숨겨진 강자'),
      ('PROTAGONIST', '능글맞은'), ('PROTAGONIST', '망나니'), ('PROTAGONIST', '보호자형'), ('PROTAGONIST', '선한 인물'),
      ('PROTAGONIST', '집요한'), ('PROTAGONIST', '두뇌파'), ('PROTAGONIST', '겉은 약해도 강한'), ('PROTAGONIST', '악한 인물'),
      ('SUPPORTING_CHARACTER', '흑막'), ('SUPPORTING_CHARACTER', '충성스러운'), ('SUPPORTING_CHARACTER', '수상한'),
      ('SUPPORTING_CHARACTER', '동료'), ('SUPPORTING_CHARACTER', '초월자'), ('SUPPORTING_CHARACTER', '까칠한'),
      ('SUPPORTING_CHARACTER', '스승'), ('SUPPORTING_CHARACTER', '비밀스러운'), ('SUPPORTING_CHARACTER', '호위무사'),
      ('SUPPORTING_CHARACTER', '귀족'), ('SUPPORTING_CHARACTER', '조력자'), ('SUPPORTING_CHARACTER', '장난기 많은'),
      ('SUPPORTING_CHARACTER', '후회하는'), ('SUPPORTING_CHARACTER', '능글맞은')
  );

-- 2) 이름 변경 (8). name + normalized_name 동시 UPDATE(정규화 키가 어긋나면 유니크·연결이 깨진다).
UPDATE story_creation_tags SET name = '생존', normalized_name = '생존', updated_at = now()
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE' AND name = '생존물';
UPDATE story_creation_tags SET name = '학원', normalized_name = '학원', updated_at = now()
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE' AND name = '학원물';
UPDATE story_creation_tags SET name = '헌터', normalized_name = '헌터', updated_at = now()
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE' AND name = '헌터물';
UPDATE story_creation_tags SET name = '재벌', normalized_name = '재벌', updated_at = now()
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE' AND name = '재벌물';
UPDATE story_creation_tags SET name = '게임', normalized_name = '게임', updated_at = now()
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE' AND name = '게임 판타지';
UPDATE story_creation_tags SET name = '책임감', normalized_name = '책임감', updated_at = now()
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'PROTAGONIST' AND name = '책임감 있는';
UPDATE story_creation_tags SET name = '트라우마', normalized_name = '트라우마', updated_at = now()
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'PROTAGONIST' AND name = '상처 있는';
UPDATE story_creation_tags SET name = '계획적인', normalized_name = '계획적인', updated_at = now()
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'PROTAGONIST' AND name = '계략적인';

-- 3) 카테고리 이동 (5). GENRE → PROTAGONIST (주인공 상태 태그).
UPDATE story_creation_tags
SET tag_type = 'PROTAGONIST', updated_at = now()
WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE'
  AND name IN ('회귀', '빙의', '환생', '시한부', '시스템');

-- 3b) 이동 태그의 세션 연결 보정 (KNK-846 컴파일 경로 유실 방지).
--     V56~V57 창에서 새 계약(KNK-845/846) 세션이 회귀·빙의·환생·시한부·시스템을 '장르'로 저장하면 character_id가 NULL이다.
--     이 태그가 이제 PROTAGONIST라, 컴파일 조립부는 장르 필터(category==GENRE)에도 character_id별 특징 조회에도 이 행을
--     넣지 못해 사용자 선택이 유실된다. 그래서 이동 태그의 character_id NULL 행을 그 세션의 주인공 인물 행에 재연결한다.
--     - (a) 주인공 인물 행이 있는 세션(=새 계약 세션)만 재연결한다. 구 계약 세션(인물 행 없음)은 NULL을 유지한다
--           — 846의 category 폴백이 이 태그들을 주인공 특징으로 복원하므로 그대로가 맞다.
--     - (b) 주인공이 이미 같은 태그를 가지면 재연결이 (creation_session_id, character_id, tag_id) 유니크를 위반하므로,
--           재연결 대신 NULL 행을 삭제해 dedup한다(먼저 수행).
--     - (c) 주인공 인물 행이 복수면 role·sort_order·id 정렬의 첫 행을 쓴다(앱 조회 정렬과 동일).
WITH moved_tags AS (
    SELECT id FROM story_creation_tags
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'PROTAGONIST'
      AND name IN ('회귀', '빙의', '환생', '시한부', '시스템')
),
first_protagonist AS (
    SELECT DISTINCT ON (creation_session_id) creation_session_id, id AS character_id
    FROM story_creation_characters
    WHERE role = 'PROTAGONIST'
    ORDER BY creation_session_id, sort_order, id
)
DELETE FROM story_creation_session_tags st
USING first_protagonist fp
WHERE st.character_id IS NULL
  AND st.tag_id IN (SELECT id FROM moved_tags)
  AND fp.creation_session_id = st.creation_session_id
  AND EXISTS (
      SELECT 1 FROM story_creation_session_tags e
      WHERE e.creation_session_id = st.creation_session_id
        AND e.character_id = fp.character_id
        AND e.tag_id = st.tag_id
  );

WITH moved_tags AS (
    SELECT id FROM story_creation_tags
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'PROTAGONIST'
      AND name IN ('회귀', '빙의', '환생', '시한부', '시스템')
),
first_protagonist AS (
    SELECT DISTINCT ON (creation_session_id) creation_session_id, id AS character_id
    FROM story_creation_characters
    WHERE role = 'PROTAGONIST'
    ORDER BY creation_session_id, sort_order, id
)
UPDATE story_creation_session_tags st
SET character_id = fp.character_id
FROM first_protagonist fp
WHERE st.character_id IS NULL
  AND st.tag_id IN (SELECT id FROM moved_tags)
  AND fp.creation_session_id = st.creation_session_id;

-- 4) BL 승격 (1). 운영에 CUSTOM GENRE 'BL'(정규화 'bl')이 있으면 PREDEFINED로 승격, 없으면 신규 삽입.
--    유니크 키에 tag_source가 포함돼 그냥 INSERT하면 같은 이름이 두 행으로 공존하므로 UPDATE 승격이 우선이다.
--    가드: PREDEFINED GENRE 'bl'이 이미 있는 혼재 편차 상태면 승격 UPDATE가 유니크(tag_source,tag_type,normalized_name)를
--    위반하므로 승격을 건너뛴다(이미 PREDEFINED가 있어 목표 상태는 충족, CUSTOM 행은 그대로 둔다 — B2).
UPDATE story_creation_tags
SET tag_source = 'PREDEFINED', name = 'BL', is_active = TRUE, updated_at = now()
WHERE tag_source = 'CUSTOM' AND tag_type = 'GENRE' AND normalized_name = 'bl'
  AND NOT EXISTS (
      SELECT 1 FROM story_creation_tags p
      WHERE p.tag_source = 'PREDEFINED' AND p.tag_type = 'GENRE' AND p.normalized_name = 'bl'
  );

INSERT INTO story_creation_tags (tag_type, name, tag_source, sort_order, is_active, normalized_name)
SELECT 'GENRE', 'BL', 'PREDEFINED', 120, TRUE, 'bl'
WHERE NOT EXISTS (
    SELECT 1 FROM story_creation_tags
    WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE' AND normalized_name = 'bl'
);

-- 5) 신규 (10) INSERT (PREDEFINED). sort_order는 6단계에서 최종 부여하므로 임시값(0)으로 넣는다.
INSERT INTO story_creation_tags (tag_type, name, tag_source, sort_order, is_active, normalized_name)
VALUES
    ('GENRE', '로맨스', 'PREDEFINED', 0, TRUE, '로맨스'),
    ('GENRE', '중세 판타지', 'PREDEFINED', 0, TRUE, '중세판타지'),
    ('GENRE', 'SF', 'PREDEFINED', 0, TRUE, 'sf'),
    ('GENRE', '탈출', 'PREDEFINED', 0, TRUE, '탈출'),
    ('PROTAGONIST', '츤데레', 'PREDEFINED', 0, TRUE, '츤데레'),
    ('PROTAGONIST', '아이돌', 'PREDEFINED', 0, TRUE, '아이돌'),
    ('SUPPORTING_CHARACTER', '츤데레', 'PREDEFINED', 0, TRUE, '츤데레'),
    ('SUPPORTING_CHARACTER', '연인', 'PREDEFINED', 0, TRUE, '연인'),
    ('SUPPORTING_CHARACTER', '짝사랑', 'PREDEFINED', 0, TRUE, '짝사랑'),
    ('SUPPORTING_CHARACTER', '천재', 'PREDEFINED', 0, TRUE, '천재');

-- 6) 최종 목록(40)의 sort_order를 티켓 순서대로 10 단위 재부여하고 is_active를 TRUE로 확정한다.
--    노출 조회가 category → sort_order → id로 정렬하므로 sort_order가 곧 화면 순서다.
UPDATE story_creation_tags t
SET sort_order = v.sort_order, is_active = TRUE, updated_at = now()
FROM (VALUES
    ('GENRE', '로맨스 판타지', 10),
    ('GENRE', '현대 판타지', 20),
    ('GENRE', '로맨스', 30),
    ('GENRE', '무협', 40),
    ('GENRE', '헌터', 50),
    ('GENRE', '학원', 60),
    ('GENRE', '중세 판타지', 70),
    ('GENRE', '재벌', 80),
    ('GENRE', '게임', 90),
    ('GENRE', '아포칼립스', 100),
    ('GENRE', '생존', 110),
    ('GENRE', 'BL', 120),
    ('GENRE', 'SF', 130),
    ('GENRE', '요리', 140),
    ('GENRE', '탈출', 150),
    ('PROTAGONIST', '회귀', 10),
    ('PROTAGONIST', '빙의', 20),
    ('PROTAGONIST', '환생', 30),
    ('PROTAGONIST', '먼치킨', 40),
    ('PROTAGONIST', '천재', 50),
    ('PROTAGONIST', '성장형', 60),
    ('PROTAGONIST', '시스템', 70),
    ('PROTAGONIST', '츤데레', 80),
    ('PROTAGONIST', '다정한', 90),
    ('PROTAGONIST', '트라우마', 100),
    ('PROTAGONIST', '정의로운', 110),
    ('PROTAGONIST', '책임감', 120),
    ('PROTAGONIST', '계획적인', 130),
    ('PROTAGONIST', '시한부', 140),
    ('PROTAGONIST', '아이돌', 150),
    ('SUPPORTING_CHARACTER', '집착하는', 10),
    ('SUPPORTING_CHARACTER', '츤데레', 20),
    ('SUPPORTING_CHARACTER', '다정한', 30),
    ('SUPPORTING_CHARACTER', '소꿉친구', 40),
    ('SUPPORTING_CHARACTER', '짝사랑', 50),
    ('SUPPORTING_CHARACTER', '연인', 60),
    ('SUPPORTING_CHARACTER', '라이벌', 70),
    ('SUPPORTING_CHARACTER', '사랑스러운', 80),
    ('SUPPORTING_CHARACTER', '가족', 90),
    ('SUPPORTING_CHARACTER', '천재', 100)
) AS v(tag_type, name, sort_order)
WHERE t.tag_source = 'PREDEFINED' AND t.tag_type = v.tag_type AND t.name = v.name;
