-- KNK-409: GENRE 마스터에 '요리'를 추가한다.
--
-- 설계 메모
-- * 팀 제작 썸네일 자산 중 1장이 '요리' 장르인데 마스터(V13)에 대응 장르가 없었다.
--   이미지 카탈로그의 장르는 마스터 태그를 FK로 참조하므로(V45), 자산을 등재하려면 마스터에 먼저 있어야 한다.
-- * 신규 선택지로도 노출된다(조회 API는 is_active = TRUE만 노출). 스토리 제작 화면의 장르 목록이 21종이 된다.
-- * V13과 같은 UPSERT 패턴이라 멱등하다. sort_order는 기존 마지막(성장물 200) 다음인 210.

INSERT INTO story_creation_tags (tag_type, name, tag_source, sort_order, is_active)
VALUES ('GENRE', '요리', 'PREDEFINED', 210, TRUE)
ON CONFLICT ON CONSTRAINT uq_story_creation_tags_source_type_name
DO UPDATE SET
    sort_order = EXCLUDED.sort_order,
    is_active = TRUE,
    updated_at = now();
