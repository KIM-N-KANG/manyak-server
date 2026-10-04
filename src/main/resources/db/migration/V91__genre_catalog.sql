-- KNK-1537: 제공 장르 197개, 대표 15개, 검색 별칭. CUSTOM과 인물 특징은 보존한다.
-- 재실행 시 같은 ID와 결과를 유지한다. Flyway의 단일 트랜잭션에서 임시 테이블을 사용한다.
ALTER TABLE story_creation_tags ADD COLUMN IF NOT EXISTS featured_order INTEGER;
CREATE TABLE IF NOT EXISTS story_creation_tag_aliases (
    id BIGSERIAL PRIMARY KEY,
    tag_id BIGINT NOT NULL REFERENCES story_creation_tags(id),
    alias VARCHAR(30) NOT NULL,
    normalized_alias VARCHAR(60) NOT NULL UNIQUE
);
CREATE INDEX IF NOT EXISTS idx_story_creation_tag_aliases_tag_id ON story_creation_tag_aliases(tag_id);

CREATE TEMP TABLE knk1537_catalog (name TEXT PRIMARY KEY, sort_order INTEGER, featured_order INTEGER) ON COMMIT DROP;
INSERT INTO knk1537_catalog (name, sort_order, featured_order) VALUES
    ('가이드버스', 10, NULL),
    ('감금', 20, NULL),
    ('강단형', 30, NULL),
    ('건장한체격', 40, NULL),
    ('게이트', 50, NULL),
    ('게임판타지', 60, NULL),
    ('경영물', 70, NULL),
    ('경찰', 80, NULL),
    ('계략', 90, NULL),
    ('계약결혼', 100, 1),
    ('계약연애', 110, NULL),
    ('고구마', 120, NULL),
    ('공포', 130, NULL),
    ('관계이탈', 140, NULL),
    ('괴담', 150, 2),
    ('구원', 160, NULL),
    ('군대물', 170, NULL),
    ('궁정물', 180, NULL),
    ('귀족', 190, NULL),
    ('귀환', 200, NULL),
    ('금발', 210, NULL),
    ('기억상실', 220, NULL),
    ('나이차이', 230, NULL),
    ('나폴리탄괴담', 240, NULL),
    ('남성향', 250, NULL),
    ('남장', 260, NULL),
    ('냉정', 270, NULL),
    ('너드', 280, NULL),
    ('노맨스', 290, NULL),
    ('느와르', 300, NULL),
    ('능글', 310, NULL),
    ('능력캐', 320, NULL),
    ('다정', 330, NULL),
    ('달달', 340, NULL),
    ('대체역사', 350, NULL),
    ('대형견', 360, NULL),
    ('던전', 370, NULL),
    ('데스게임', 380, NULL),
    ('도피형', 390, NULL),
    ('동거', 400, NULL),
    ('동양풍', 410, NULL),
    ('드라마', 420, NULL),
    ('디스토피아', 430, NULL),
    ('로맨스', 440, NULL),
    ('로맨스판타지', 450, 3),
    ('로맨틱코미디', 460, NULL),
    ('마법', 470, NULL),
    ('마법사물', 480, NULL),
    ('망나니', 490, NULL),
    ('먹방', 500, NULL),
    ('먼치킨', 510, 4),
    ('메카', 520, NULL),
    ('모험', 530, NULL),
    ('무심', 540, NULL),
    ('무협', 550, NULL),
    ('미남형', 560, NULL),
    ('미스터리', 570, NULL),
    ('미인형', 580, NULL),
    ('밀리터리', 590, NULL),
    ('배드엔딩', 600, NULL),
    ('배틀연애', 610, NULL),
    ('뱀파이어', 620, NULL),
    ('범죄조직', 630, NULL),
    ('법정물', 640, NULL),
    ('법조계', 650, NULL),
    ('병맛', 660, NULL),
    ('병약', 670, NULL),
    ('복수', 680, 5),
    ('복흑', 690, NULL),
    ('부둥부둥', 700, NULL),
    ('북부대공', 710, NULL),
    ('브로맨스', 720, NULL),
    ('비밀연애', 730, NULL),
    ('빙의', 740, 6),
    ('사건물', 750, NULL),
    ('사극', 760, NULL),
    ('사내연애', 770, NULL),
    ('사이다', 780, NULL),
    ('사이버펑크', 790, NULL),
    ('사제관계', 800, NULL),
    ('삼각관계', 810, 7),
    ('삽질', 820, NULL),
    ('상처', 830, NULL),
    ('상태창', 840, NULL),
    ('새드엔딩', 850, NULL),
    ('생존', 860, NULL),
    ('서양풍', 870, NULL),
    ('선결혼후연애', 880, NULL),
    ('선협', 890, NULL),
    ('성장물', 900, NULL),
    ('성좌물', 910, NULL),
    ('소꿉친구', 920, NULL),
    ('소심', 930, NULL),
    ('수사물', 940, NULL),
    ('수인', 950, NULL),
    ('순애', 960, NULL),
    ('스릴러', 970, NULL),
    ('스트리머', 980, NULL),
    ('스팀펑크', 990, NULL),
    ('스포츠물', 1000, NULL),
    ('시대물', 1010, NULL),
    ('시련형주인공', 1020, NULL),
    ('시리어스', 1030, NULL),
    ('시스템', 1040, NULL),
    ('시한부', 1050, NULL),
    ('신분차이', 1060, NULL),
    ('신파', 1070, NULL),
    ('신화물', 1080, NULL),
    ('쌍방짝사랑', 1090, NULL),
    ('아이돌물', 1100, NULL),
    ('아카데미물', 1110, NULL),
    ('아포칼립스', 1120, 8),
    ('악녀물', 1130, NULL),
    ('악역', 1140, NULL),
    ('암흑가', 1150, NULL),
    ('애절', 1160, NULL),
    ('애증', 1170, NULL),
    ('액션', 1180, NULL),
    ('얀데레', 1190, NULL),
    ('엇갈린짝사랑', 1200, NULL),
    ('여성향', 1210, NULL),
    ('여장', 1220, NULL),
    ('역사물', 1230, NULL),
    ('역하렘', 1240, 10),
    ('연상', 1250, NULL),
    ('연예계물', 1260, NULL),
    ('연하', 1270, NULL),
    ('열린결말', 1280, NULL),
    ('영지물', 1290, NULL),
    ('오만', 1300, NULL),
    ('오컬트', 1310, NULL),
    ('오피스물', 1320, NULL),
    ('왕족', 1330, NULL),
    ('외유내강', 1340, NULL),
    ('요리', 1350, NULL),
    ('유사가족', 1360, NULL),
    ('유치함', 1370, NULL),
    ('육아', 1380, NULL),
    ('의료물', 1390, NULL),
    ('이기적', 1400, NULL),
    ('이세계', 1410, NULL),
    ('인외', 1420, NULL),
    ('인터넷방송', 1430, NULL),
    ('일상물', 1440, NULL),
    ('잔잔', 1450, NULL),
    ('장난꾸러기', 1460, NULL),
    ('재벌물', 1470, NULL),
    ('재회', 1480, NULL),
    ('전생', 1490, NULL),
    ('정략결혼', 1500, NULL),
    ('정통판타지', 1510, NULL),
    ('좀비물', 1520, NULL),
    ('주도형', 1530, NULL),
    ('직진', 1540, NULL),
    ('집착', 1550, 11),
    ('짝사랑', 1560, NULL),
    ('차원이동', 1570, NULL),
    ('착각물', 1580, NULL),
    ('천재', 1590, NULL),
    ('철벽', 1600, NULL),
    ('첫사랑', 1610, NULL),
    ('초능력', 1620, NULL),
    ('추리', 1630, NULL),
    ('추방', 1640, NULL),
    ('츤데레', 1650, NULL),
    ('친구에서연인', 1660, NULL),
    ('캠퍼스물', 1670, NULL),
    ('코미디', 1680, NULL),
    ('코즈믹호러', 1690, NULL),
    ('타임루프', 1700, NULL),
    ('타임슬립', 1710, NULL),
    ('탈출', 1720, 15),
    ('탑등반물', 1730, NULL),
    ('태닝', 1740, NULL),
    ('판타지', 1750, NULL),
    ('포스트아포칼립스', 1760, NULL),
    ('퓨전판타지', 1770, NULL),
    ('피폐', 1780, NULL),
    ('하렘', 1790, NULL),
    ('학원물', 1800, NULL),
    ('해피엔딩', 1810, NULL),
    ('햇살', 1820, NULL),
    ('헌터물', 1830, 12),
    ('현대물', 1840, NULL),
    ('현대판타지', 1850, 9),
    ('혐관', 1860, NULL),
    ('형사', 1870, NULL),
    ('환생', 1880, NULL),
    ('회귀', 1890, 14),
    ('후회', 1900, NULL),
    ('흑화', 1910, NULL),
    ('힐링', 1920, NULL),
    ('BL', 1930, 13),
    ('GL', 1940, NULL),
    ('HL', 1950, NULL),
    ('SF', 1960, NULL),
    ('TS', 1970, NULL);

CREATE TEMP TABLE knk1537_renames (old_key TEXT PRIMARY KEY, new_name TEXT) ON COMMIT DROP;
INSERT INTO knk1537_renames VALUES
    ('헌터', '헌터물'), ('학원', '학원물'), ('재벌', '재벌물'), ('게임', '게임판타지'),
    ('중세판타지', '정통판타지'), ('육아물', '육아'), ('복수극', '복수');

-- 활성 ID를 우선 보존한다. 둘 다 활성/비활성이면 옛 행을 그 자리 개명한다.
-- 새 이름만 존재하는 재실행에서는 옛 행이 없으므로 참조 병합 없이 넘어간다.
DO $$
DECLARE
    mapping RECORD;
    old_id BIGINT;
    new_id BIGINT;
    old_active BOOLEAN;
    new_active BOOLEAN;
    keep_id BIGINT;
    discard_id BIGINT;
BEGIN
    FOR mapping IN SELECT * FROM knk1537_renames ORDER BY old_key LOOP
        SELECT id, is_active INTO old_id, old_active FROM story_creation_tags
        WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE' AND normalized_name = mapping.old_key;
        IF old_id IS NULL THEN CONTINUE; END IF;
        SELECT id, is_active INTO new_id, new_active FROM story_creation_tags
        WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE'
          AND normalized_name = lower(regexp_replace(mapping.new_name, '[[:space:]]', '', 'g'));
        keep_id := old_id;
        IF new_id IS NOT NULL AND new_id <> old_id THEN
            IF new_active AND NOT old_active THEN
                keep_id := new_id;
                discard_id := old_id;
            ELSE
                discard_id := new_id;
            END IF;
            -- 최초 선택 위치(st.id)를 유지한다. 다른 인물의 참조는 삭제하지 않는다.
            DELETE FROM story_creation_session_tags s USING story_creation_session_tags earlier
            WHERE s.tag_id IN (keep_id, discard_id) AND earlier.tag_id IN (keep_id, discard_id)
              AND s.creation_session_id = earlier.creation_session_id
              AND s.character_id IS NOT DISTINCT FROM earlier.character_id AND s.id > earlier.id;
            UPDATE story_creation_session_tags SET tag_id = keep_id WHERE tag_id = discard_id;
            DELETE FROM image_preset_genres g USING image_preset_genres existing
            WHERE g.tag_id = discard_id AND existing.tag_id = keep_id AND g.image_preset_id = existing.image_preset_id;
            UPDATE image_preset_genres SET tag_id = keep_id WHERE tag_id = discard_id;
            UPDATE story_creation_tag_aliases SET tag_id = keep_id WHERE tag_id = discard_id;
            DELETE FROM story_creation_tags WHERE id = discard_id;
        END IF;
        UPDATE story_creation_tags SET name = mapping.new_name,
            normalized_name = lower(regexp_replace(mapping.new_name, '[[:space:]]', '', 'g')), updated_at = now()
        WHERE id = keep_id;
    END LOOP;
END $$;

-- 공백 표기 변경, 기존 비활성 재활성, 신규 삽입을 동일한 정규화 키로 처리한다.
INSERT INTO story_creation_tags (tag_type, name, tag_source, normalized_name, sort_order, featured_order, is_active)
SELECT 'GENRE', name, 'PREDEFINED', lower(regexp_replace(name, '[[:space:]]', '', 'g')), sort_order, featured_order, TRUE
FROM knk1537_catalog
ON CONFLICT (tag_source, tag_type, normalized_name) DO UPDATE
SET name = EXCLUDED.name, sort_order = EXCLUDED.sort_order, featured_order = EXCLUDED.featured_order,
    is_active = TRUE, updated_at = now();

UPDATE story_creation_tags SET is_active = FALSE, featured_order = NULL, updated_at = now()
WHERE tag_source = 'PREDEFINED' AND tag_type = 'GENRE'
  AND normalized_name NOT IN (SELECT lower(regexp_replace(name, '[[:space:]]', '', 'g')) FROM knk1537_catalog);

-- 운영 로어북의 내용과 연결은 유지하고 장르 문자열만 동기화한다.
UPDATE lorebooks l SET genre = r.new_name
FROM knk1537_renames r WHERE lower(regexp_replace(l.genre, '[[:space:]]', '', 'g')) = r.old_key;
UPDATE lorebooks SET genre = '악역' WHERE lower(regexp_replace(genre, '[[:space:]]', '', 'g')) = '악역물';
UPDATE lorebooks l SET genre = c.name FROM knk1537_catalog c
WHERE lower(regexp_replace(l.genre, '[[:space:]]', '', 'g')) = lower(regexp_replace(c.name, '[[:space:]]', '', 'g'));

-- ALIAS SEEDS
INSERT INTO story_creation_tag_aliases (tag_id, alias, normalized_alias)
SELECT t.id, a.alias, lower(regexp_replace(a.alias, '[[:space:]]', '', 'g'))
FROM (VALUES
    ('헌터', '헌터물'), ('학원', '학원물'), ('재벌', '재벌물'), ('게임', '게임판타지'),
    ('중세판타지', '정통판타지'), ('육아물', '육아'), ('복수극', '복수'), ('악역물', '악역'),
    ('로판', '로맨스판타지'), ('현판', '현대판타지'), ('겜판', '게임판타지'), ('퓨판', '퓨전판타지'),
    ('정판', '정통판타지'), ('로코', '로맨틱코미디'), ('아포', '아포칼립스'),
    ('비엘', 'BL'), ('지엘', 'GL'), ('에스에프', 'SF')
) AS a(alias, name)
JOIN story_creation_tags t ON t.tag_source = 'PREDEFINED' AND t.tag_type = 'GENRE'
    AND t.normalized_name = lower(regexp_replace(a.name, '[[:space:]]', '', 'g'))
ON CONFLICT (normalized_alias) DO UPDATE SET tag_id = EXCLUDED.tag_id, alias = EXCLUDED.alias;
-- END ALIAS SEEDS

DROP TABLE knk1537_renames;
DROP TABLE knk1537_catalog;
