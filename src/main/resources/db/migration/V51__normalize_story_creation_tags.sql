-- KNK-717: 커스텀 태그 정규화 키(normalized_name) 도입과 기존 중복 병합(스펙 §4-3-2).
--
-- 설계 메모
-- * 정규화 키 = trim → 내부 공백 전부 제거 → lowercase. 런타임 규칙(StoryCreationTag.normalize)과 같아야 한다.
-- * 표시명 name은 최초 입력 원문을 유지한다(병합 시에도 정본 행의 name을 건드리지 않는다).
-- * 유니크 제약을 (tag_source, tag_type, name) → (tag_source, tag_type, normalized_name)으로 교체하므로,
--   교체 전에 정규화 키가 겹치는 기존 행을 정본 1행으로 병합해야 한다.
-- * PREDEFINED에도 겹침이 있다: V2 시드의 '현대판타지'·'로맨스판타지'와 V13이 추가한 '현대 판타지'·'로맨스 판타지'.
--   V13이 옛 행을 is_active=FALSE로 비활성화했으므로 정본은 "활성 행 우선, 그다음 최소 id"로 고른다.
--   (최소 id를 그대로 쓰면 비활성 행이 정본이 되어 활성 장르가 태그 목록에서 사라진다.)
-- * CUSTOM 행은 같은 카테고리 PREDEFINED와 키가 겹치면 그 PREDEFINED 행으로 연결한다(런타임 연결 규칙과 동일).

ALTER TABLE story_creation_tags
    ADD COLUMN normalized_name VARCHAR(30);

UPDATE story_creation_tags
SET normalized_name = lower(regexp_replace(name, '[[:space:]]', '', 'g'));

ALTER TABLE story_creation_tags
    ALTER COLUMN normalized_name SET NOT NULL;

-- 1) 사라질 행 → 정본 행 매핑을 만든다.
CREATE TEMP TABLE knk717_tag_merge (
    from_id BIGINT PRIMARY KEY,
    to_id BIGINT NOT NULL
) ON COMMIT DROP;

WITH canonical AS (
    SELECT tag_source,
           tag_type,
           normalized_name,
           COALESCE(min(id) FILTER (WHERE is_active), min(id)) AS id
    FROM story_creation_tags
    GROUP BY tag_source, tag_type, normalized_name
)
INSERT INTO knk717_tag_merge (from_id, to_id)
SELECT t.id, COALESCE(predefined.id, same_source.id)
FROM story_creation_tags t
JOIN canonical same_source
  ON same_source.tag_source = t.tag_source
 AND same_source.tag_type = t.tag_type
 AND same_source.normalized_name = t.normalized_name
LEFT JOIN canonical predefined
  ON t.tag_source = 'CUSTOM'
 AND predefined.tag_source = 'PREDEFINED'
 AND predefined.tag_type = t.tag_type
 AND predefined.normalized_name = t.normalized_name
WHERE COALESCE(predefined.id, same_source.id) <> t.id;

-- 2) 재지정하면 (creation_session_id, tag_id) 유니크가 깨지는 행(같은 세션이 변형 표기를 중복 선택)은 최소 id만 남긴다.
DELETE FROM story_creation_session_tags st
USING (
    SELECT s.id,
           row_number() OVER (
               PARTITION BY s.creation_session_id, COALESCE(m.to_id, s.tag_id)
               ORDER BY s.id
           ) AS rn
    FROM story_creation_session_tags s
    LEFT JOIN knk717_tag_merge m ON m.from_id = s.tag_id
) ranked
WHERE st.id = ranked.id
  AND ranked.rn > 1;

UPDATE story_creation_session_tags st
SET tag_id = m.to_id
FROM knk717_tag_merge m
WHERE st.tag_id = m.from_id;

-- 3) image_preset_genres는 PREDEFINED 장르만 참조한다. PK (image_preset_id, tag_id) 충돌분을 먼저 지우고 재지정한다.
DELETE FROM image_preset_genres g
USING (
    SELECT p.ctid AS row_ctid,
           row_number() OVER (
               PARTITION BY p.image_preset_id, COALESCE(m.to_id, p.tag_id)
               ORDER BY p.tag_id
           ) AS rn
    FROM image_preset_genres p
    LEFT JOIN knk717_tag_merge m ON m.from_id = p.tag_id
) ranked
WHERE g.ctid = ranked.row_ctid
  AND ranked.rn > 1;

UPDATE image_preset_genres g
SET tag_id = m.to_id
FROM knk717_tag_merge m
WHERE g.tag_id = m.from_id;

-- 4) 참조가 사라진 중복 행을 지우고 유니크 제약을 정규화 키로 교체한다.
DELETE FROM story_creation_tags t
USING knk717_tag_merge m
WHERE t.id = m.from_id;

ALTER TABLE story_creation_tags
    DROP CONSTRAINT uq_story_creation_tags_source_type_name;

ALTER TABLE story_creation_tags
    ADD CONSTRAINT uq_story_creation_tags_source_type_normalized_name
        UNIQUE (tag_source, tag_type, normalized_name);
