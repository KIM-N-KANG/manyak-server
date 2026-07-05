-- 일반 모드 초안(draft) 기반 도입(KNK-401):
--   - 스토리에 등록 상태(status: DRAFT/PUBLISHED)와 공개 범위(visibility: PUBLIC/PRIVATE)를 부여한다.
--   - 세계관 설정(story_settings)을 초안 단계에서 부분 저장할 수 있게 world/character의 NOT NULL을 완화한다.
--
-- 공개 조회(GET /stories/{id}, /stories/batch)는 앱에서 PUBLISHED AND PUBLIC만 노출한다.
-- 기존 행은 모두 발행·공개 상태로 보존하기 위해 DEFAULT를 PUBLISHED/PUBLIC으로 둔다(간편 제작 스토리 포함).
-- 초안은 앱이 status=DRAFT, visibility=PRIVATE로 명시 생성한다.

ALTER TABLE stories ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE stories ADD CONSTRAINT ck_stories_status CHECK (status IN ('DRAFT', 'PUBLISHED'));

ALTER TABLE stories ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE stories ADD CONSTRAINT ck_stories_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE'));

-- 초안은 세계관을 탭별로 부분 저장하므로 world/character가 아직 비어 있을 수 있다.
ALTER TABLE story_settings ALTER COLUMN world_setting DROP NOT NULL;
ALTER TABLE story_settings ALTER COLUMN character_setting DROP NOT NULL;
