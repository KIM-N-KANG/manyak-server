-- V91 적용 DB 전용. psql -v ON_ERROR_STOP=1 -f scripts/verify-genre-catalog-migration.sql
-- 운영 DB에서 실행하지 않는다. 모든 fixture와 변경은 마지막에 ROLLBACK한다.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE probe_cases(old_name text, new_name text, old_active boolean, new_active boolean,
    old_id bigint, new_id bigint, keep_id bigint, discard_id bigint, session_id bigint);
INSERT INTO probe_cases(old_name,new_name,old_active,new_active) VALUES
    ('헌터','헌터물',true,false), ('학원','학원물',false,true),
    ('재벌','재벌물',false,false), ('게임','게임판타지',true,true);
CREATE TEMP TABLE probe_presets(id bigint PRIMARY KEY);
DO $$
DECLARE c record; old_tag bigint; new_tag bigint; sid bigint; cid bigint; pic bigint; n integer;
BEGIN
    FOR c IN SELECT * FROM probe_cases LOOP
        SELECT id INTO STRICT old_tag FROM story_creation_tags
            WHERE tag_source='PREDEFINED' AND tag_type='GENRE' AND name=c.new_name;
        UPDATE story_creation_tags SET name=c.old_name, normalized_name=c.old_name, is_active=c.old_active WHERE id=old_tag;
        INSERT INTO story_creation_tags(tag_type,name,tag_source,normalized_name,is_active,sort_order)
            VALUES ('GENRE',c.new_name,'PREDEFINED',c.new_name,c.new_active,0) RETURNING id INTO new_tag;
        INSERT INTO story_creation_sessions(status) VALUES ('STORYLINES_GENERATED') RETURNING id INTO sid;
        -- NULL 인물 중복은 old가 먼저, 같은 인물 중복은 new가 먼저다.
        INSERT INTO story_creation_session_tags(creation_session_id,tag_id) VALUES (sid,old_tag),(sid,new_tag);
        FOR n IN 1..3 LOOP
            INSERT INTO story_creation_characters(creation_session_id,role,sort_order,name)
                VALUES(sid,'SUPPORTING_CHARACTER',n,'probe_' || n) RETURNING id INTO cid;
            IF n IN (1,3) THEN
                INSERT INTO story_creation_session_tags(creation_session_id,character_id,tag_id) VALUES(sid,cid,new_tag);
            END IF;
            IF n IN (1,2) THEN
                INSERT INTO story_creation_session_tags(creation_session_id,character_id,tag_id) VALUES(sid,cid,old_tag);
            END IF;
        END LOOP;
        -- 같은 이미지의 중복과 서로 다른 이미지의 단독 FK를 모두 검증한다.
        FOR n IN 1..3 LOOP
            INSERT INTO image_presets(image_key,type) VALUES('knk1537_probe_' || sid || '_' || n,'THUMBNAIL') RETURNING id INTO pic;
            INSERT INTO probe_presets VALUES(pic);
            IF n IN (1,2) THEN INSERT INTO image_preset_genres VALUES(pic,old_tag); END IF;
            IF n IN (1,3) THEN INSERT INTO image_preset_genres VALUES(pic,new_tag); END IF;
        END LOOP;
        INSERT INTO story_creation_tag_aliases(tag_id,alias,normalized_alias) VALUES
            (old_tag,'probe_old_' || c.old_name,'probe_old_' || c.old_name),
            (new_tag,'probe_new_' || c.old_name,'probe_new_' || c.old_name);
        UPDATE probe_cases SET old_id=old_tag,new_id=new_tag,session_id=sid,
            keep_id=CASE WHEN NOT c.old_active AND c.new_active THEN new_tag ELSE old_tag END,
            discard_id=CASE WHEN NOT c.old_active AND c.new_active THEN old_tag ELSE new_tag END
            WHERE old_name=c.old_name;
    END LOOP;
END $$;
INSERT INTO story_creation_tags(tag_type,name,tag_source,normalized_name,is_active,sort_order)
    VALUES('GENRE','개인장르','CUSTOM','개인장르',true,0);
INSERT INTO lorebooks(name,genre,content) VALUES
    ('probe_hunter','헌 터','유지'),('probe_medieval','중세 판타지','유지'),('probe_game','게임 판타지','유지');
CREATE TEMP TABLE probe_protected AS SELECT * FROM story_creation_tags WHERE tag_source='CUSTOM' OR tag_type<>'GENRE';
CREATE TEMP TABLE probe_expected_sessions AS
    SELECT min(st.id) AS id, st.creation_session_id, st.character_id, c.keep_id AS tag_id
    FROM story_creation_session_tags st JOIN probe_cases c ON c.session_id=st.creation_session_id
    GROUP BY st.creation_session_id,st.character_id,c.keep_id;
CREATE TEMP TABLE probe_expected_images AS
    SELECT DISTINCT ip.image_preset_id,c.keep_id AS tag_id FROM image_preset_genres ip
    JOIN probe_cases c ON ip.tag_id IN(c.old_id,c.new_id);
CREATE TEMP TABLE probe_expected_aliases AS
    SELECT a.id,a.alias,a.normalized_alias,c.keep_id AS tag_id FROM story_creation_tag_aliases a
    JOIN probe_cases c ON a.tag_id IN(c.old_id,c.new_id);
CREATE FUNCTION pg_temp.assert_probe() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM probe_cases c LEFT JOIN story_creation_tags t ON t.id=c.keep_id
        WHERE t.id IS NULL OR t.name<>c.new_name OR NOT t.is_active)
        OR EXISTS(SELECT 1 FROM story_creation_tags t JOIN probe_cases c ON t.id=c.discard_id)
        THEN RAISE EXCEPTION 'wrong survivor ID or discarded row retained'; END IF;
    IF EXISTS((SELECT * FROM probe_expected_sessions EXCEPT
        SELECT id,creation_session_id,character_id,tag_id FROM story_creation_session_tags)
        UNION ALL (SELECT id,creation_session_id,character_id,tag_id FROM story_creation_session_tags
        WHERE creation_session_id IN(SELECT session_id FROM probe_cases) EXCEPT SELECT * FROM probe_expected_sessions))
        THEN RAISE EXCEPTION 'character boundary, NULL dedup or earliest selection row changed'; END IF;
    IF (SELECT count(*) FROM probe_expected_sessions)<>16 THEN RAISE EXCEPTION 'invalid session fixture'; END IF;
    IF EXISTS((SELECT * FROM probe_expected_images EXCEPT SELECT * FROM image_preset_genres)
        UNION ALL (SELECT * FROM image_preset_genres WHERE image_preset_id IN(SELECT id FROM probe_presets)
        EXCEPT SELECT * FROM probe_expected_images)) THEN RAISE EXCEPTION 'image FK loss or duplicate'; END IF;
    IF EXISTS(SELECT 1 FROM probe_expected_aliases e LEFT JOIN story_creation_tag_aliases a ON a.id=e.id
        WHERE a.id IS NULL OR a.tag_id<>e.tag_id OR a.alias<>e.alias OR a.normalized_alias<>e.normalized_alias)
        THEN RAISE EXCEPTION 'alias FK or identity changed'; END IF;
    IF EXISTS(SELECT 1 FROM story_creation_session_tags s LEFT JOIN story_creation_tags t ON t.id=s.tag_id
        LEFT JOIN story_creation_sessions cs ON cs.id=s.creation_session_id
        LEFT JOIN story_creation_characters ch ON ch.id=s.character_id
        WHERE t.id IS NULL OR cs.id IS NULL OR (s.character_id IS NOT NULL AND (ch.id IS NULL OR ch.creation_session_id<>s.creation_session_id)))
        OR EXISTS(SELECT 1 FROM image_preset_genres p LEFT JOIN story_creation_tags t ON t.id=p.tag_id
            LEFT JOIN image_presets i ON i.id=p.image_preset_id WHERE t.id IS NULL OR i.id IS NULL)
        OR EXISTS(SELECT 1 FROM story_creation_tag_aliases a LEFT JOIN story_creation_tags t ON t.id=a.tag_id WHERE t.id IS NULL)
        THEN RAISE EXCEPTION 'orphan FK'; END IF;
    IF EXISTS((SELECT * FROM probe_protected EXCEPT SELECT * FROM story_creation_tags)
        UNION ALL (SELECT * FROM story_creation_tags WHERE tag_source='CUSTOM' OR tag_type<>'GENRE' EXCEPT SELECT * FROM probe_protected))
        THEN RAISE EXCEPTION 'protected tag changed'; END IF;
    IF (SELECT count(*) FROM story_creation_tags WHERE tag_source='PREDEFINED' AND tag_type='GENRE' AND is_active)<>197
        OR (SELECT count(*) FROM story_creation_tags WHERE featured_order IS NOT NULL)<>15
        OR (SELECT count(*) FROM story_creation_tag_aliases)<>26 THEN RAISE EXCEPTION 'catalog or alias count'; END IF;
    IF (SELECT genre FROM lorebooks WHERE name='probe_hunter')<>'헌터물'
        OR (SELECT genre FROM lorebooks WHERE name='probe_medieval')<>'정통판타지'
        OR (SELECT genre FROM lorebooks WHERE name='probe_game')<>'게임판타지'
        THEN RAISE EXCEPTION 'lorebook mapping'; END IF;
END $$;
\echo First migration pass
\ir ../src/main/resources/db/migration/V91__genre_catalog.sql
SET CONSTRAINTS ALL IMMEDIATE;
SELECT pg_temp.assert_probe();
SELECT old_name,new_name,old_active,new_active,old_id,new_id,keep_id,discard_id FROM probe_cases ORDER BY old_name;
SELECT s.id,s.creation_session_id,s.character_id,s.tag_id FROM story_creation_session_tags s
    WHERE creation_session_id IN(SELECT session_id FROM probe_cases) ORDER BY s.id;
CREATE TEMP TABLE probe_first_tags AS SELECT id,name,normalized_name,tag_source,tag_type,is_active,sort_order,featured_order FROM story_creation_tags;
CREATE TEMP TABLE probe_first_sessions AS SELECT * FROM story_creation_session_tags;
CREATE TEMP TABLE probe_first_images AS SELECT * FROM image_preset_genres;
CREATE TEMP TABLE probe_first_aliases AS SELECT * FROM story_creation_tag_aliases;
\echo Second migration pass
\ir ../src/main/resources/db/migration/V91__genre_catalog.sql
SET CONSTRAINTS ALL IMMEDIATE;
SELECT pg_temp.assert_probe();
DO $$ BEGIN
    IF EXISTS((SELECT * FROM probe_first_tags EXCEPT SELECT id,name,normalized_name,tag_source,tag_type,is_active,sort_order,featured_order FROM story_creation_tags)
        UNION ALL (SELECT id,name,normalized_name,tag_source,tag_type,is_active,sort_order,featured_order FROM story_creation_tags EXCEPT SELECT * FROM probe_first_tags))
        OR EXISTS((SELECT * FROM probe_first_sessions EXCEPT SELECT * FROM story_creation_session_tags) UNION ALL (SELECT * FROM story_creation_session_tags EXCEPT SELECT * FROM probe_first_sessions))
        OR EXISTS((SELECT * FROM probe_first_images EXCEPT SELECT * FROM image_preset_genres) UNION ALL (SELECT * FROM image_preset_genres EXCEPT SELECT * FROM probe_first_images))
        OR EXISTS((SELECT * FROM probe_first_aliases EXCEPT SELECT * FROM story_creation_tag_aliases) UNION ALL (SELECT * FROM story_creation_tag_aliases EXCEPT SELECT * FROM probe_first_aliases))
        THEN RAISE EXCEPTION 'second execution changed IDs or references'; END IF;
END $$;
\echo PASS all four active-state combinations, character scopes, FK and two-pass assertions
ROLLBACK;
