-- 시작 설정 복수화(스토리당 1:1 → 1:N) + 외부 식별자 public_id 부여(스펙 §4-3 · 간극표 B14, KNK-515).
-- 엔딩(start_setting 스코프, V30)·추천 입력(start_setting 스코프, V4)은 이미 시작 설정 소유라 복수화와 정합한다.

-- 1) 스토리당 1개 제약을 제거해 스토리당 여러 시작 설정을 허용한다.
ALTER TABLE story_start_settings DROP CONSTRAINT uq_story_start_settings_story;

-- 조회 성능을 위해 story_id 인덱스는 유지한다(유니크였다가 일반 인덱스로).
CREATE INDEX idx_story_start_settings_story ON story_start_settings (story_id);

-- 2) 외부 노출 식별자. 순차 PK를 API에 노출하지 않으므로(IDOR 방지) public_id(UUID)로 지목한다.
--    POST /chats의 startSettingId·상세 startSettings[].id가 이 값이다. 기존 행은 UUID로 backfill한다(PG13+ 코어 함수).
ALTER TABLE story_start_settings ADD COLUMN public_id UUID;
UPDATE story_start_settings SET public_id = gen_random_uuid() WHERE public_id IS NULL;
ALTER TABLE story_start_settings ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE story_start_settings ADD CONSTRAINT uq_story_start_settings_public_id UNIQUE (public_id);
