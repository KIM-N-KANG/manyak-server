-- 엔딩을 시작 설정(start_setting) 하위로 재스코프한다(KNK-419).
-- 기존 story_endings는 스토리(story_id) 소유였으나, 엔딩의 개수 상한·구성 단위를 '시작 설정 당'으로 옮긴다.
-- (스탯·추천 입력과 동일하게 start_setting_id를 부모로 삼아, 시작 설정이 1:N으로 늘어도 자연히 확장된다.)
--
-- 시작 설정은 현재 스토리당 1:1(story_start_settings.story_id UNIQUE)이라 backfill이 결정적이다.
-- 시작 설정이 있는 엔딩은 그 시작 설정으로 backfill하고, 매달 시작 설정이 없는 기존 엔딩(저작 API 도입 전의
-- 비운영 시드 데이터)은 재스코프 후 표현할 수 없으므로 제거한다.
-- 시작 설정 당 최대 10개 제약은 count 상한이라 DB가 아니라 앱에서 강제한다(교체 저장 시 검증).
-- (start_setting_id, sort_order) 유니크로 시작 설정 내 표시 순서를 유일하게 보장한다(교체 시 1..n으로 부여).

-- 1) 새 부모 컬럼을 널 허용으로 추가한 뒤 backfill한다.
ALTER TABLE story_endings ADD COLUMN start_setting_id BIGINT;

UPDATE story_endings e
SET start_setting_id = s.id
FROM story_start_settings s
WHERE s.story_id = e.story_id;

-- 매달 시작 설정이 없어 backfill되지 못한 엔딩(원래 쓰기 경로가 없던 비운영 시드)은 새 모델에서 표현할 수 없어 제거한다.
DELETE FROM story_endings WHERE start_setting_id IS NULL;

-- 2) 옛 스코프(유니크·story_id 컬럼)를 제거한다. story_id 컬럼을 지우면 인라인 FK도 함께 사라진다.
ALTER TABLE story_endings DROP CONSTRAINT uq_story_endings_order;
ALTER TABLE story_endings DROP COLUMN story_id;

-- 3) 새 스코프(start_setting_id)로 NOT NULL·FK·유니크·인덱스를 세운다. ck_story_endings_order(sort_order > 0)는 유지된다.
ALTER TABLE story_endings ALTER COLUMN start_setting_id SET NOT NULL;
ALTER TABLE story_endings
    ADD CONSTRAINT fk_story_endings_start_setting
        FOREIGN KEY (start_setting_id) REFERENCES story_start_settings (id) ON DELETE CASCADE;
ALTER TABLE story_endings
    ADD CONSTRAINT uq_story_endings_order UNIQUE (start_setting_id, sort_order);
CREATE INDEX idx_story_endings_start_setting ON story_endings (start_setting_id);
