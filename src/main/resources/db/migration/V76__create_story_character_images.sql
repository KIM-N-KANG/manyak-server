-- KNK-1126: 인물별 이미지 여러 장. 지금까지 인물은 컴파일이 만든 한 장(story_characters.image_url)뿐이었는데,
-- 소유자가 표정·상황별로 여러 장을 올릴 수 있게 되면서 1:N이 필요해졌다(스펙 §4-3-8 스토리 이미지 업로드).
--
-- image_name은 `{인물이름}_{접미}` 형식이다(접미는 1~20자 한글·영문·숫자 — 표정·상황·감정).
-- 같은 인물 안에서 유일해야 하므로 (character_id, image_name) UNIQUE를 건다. 애플리케이션이 사전 조회로
-- 409를 내고, 이 제약이 동시 등록 경합의 최종 방어선이다.
--
-- 백필: 기존 인물 이미지 한 장을 `{이름}_기본` 행으로 옮긴다. story_characters에는 이미지 이름 컬럼이 없어
-- 인물 이름에서 조립한다(스펙의 "컴파일이 만든 첫 장은 {인물이름}_기본"과 같은 값).
-- **옛 컬럼(story_characters.image_url)은 지우지 않는다** — 계약 마이그레이션 두 릴리스 규칙(7-deployment.md).
-- 이 릴리스부터 읽는 코드는 새 테이블만 보고, 컬럼 DROP은 다음 릴리스다.
CREATE TABLE story_character_images (
    id BIGSERIAL PRIMARY KEY,
    -- 외부 노출 식별자(삭제 API가 이 값을 받는다). 순차 PK는 API에 싣지 않는다.
    public_id UUID NOT NULL UNIQUE,
    character_id BIGINT NOT NULL REFERENCES story_characters(id) ON DELETE CASCADE,
    image_name VARCHAR(120) NOT NULL,
    -- 서빙 절대 URL. 프리셋과 달리 키가 uuid라 조회 때 조합하지 않고 연결 시점에 굳힌다.
    image_url TEXT NOT NULL,
    -- 표시 순서. 지금은 등록 순서(0부터)이고 재정렬 API는 없다.
    sort_order INT NOT NULL DEFAULT 0,
    -- 검수 상태. 노출·AI 전달은 APPROVED만 한다(스펙 §4-3-8 검수 게이트). 지금은 기본값이 APPROVED라
    -- 업로드가 즉시 반영되고 신고로 대응한다. 자동 검수(Rekognition)나 공개 스토리 검수(KNK-1160~1162)를
    -- 도입할 때 기본값을 PENDING으로 바꾸고 승인 경로만 붙이면 되며, 노출 코드는 손대지 않는다.
    moderation_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_character_images_name UNIQUE (character_id, image_name),
    CONSTRAINT ck_story_character_images_moderation
        CHECK (moderation_status IN ('APPROVED', 'PENDING', 'REJECTED'))
);

-- 인물별 조회(상세·편집 폼)와 스토리 전체 조인(채팅 요청)이 같은 경로를 쓴다.
CREATE INDEX idx_story_character_images_character ON story_character_images (character_id);

-- 백필 행은 이미 서비스 중이던 이미지라 APPROVED다(컬럼 기본값).
INSERT INTO story_character_images (public_id, character_id, image_name, image_url, sort_order)
SELECT gen_random_uuid(), c.id, c.name || '_기본', c.image_url, 0
FROM story_characters c
WHERE c.image_url IS NOT NULL;

-- 표지도 같은 게이트를 지난다. 생성 표지(V68)와 업로드 표지가 같은 컬럼을 쓰므로 상태도 하나면 된다.
-- 프리셋 표지는 팀 자산이라 검수 대상이 아니다 — 이 상태는 thumbnail_image_url에만 걸린다.
ALTER TABLE stories
    ADD COLUMN thumbnail_moderation_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD CONSTRAINT ck_stories_thumbnail_moderation
        CHECK (thumbnail_moderation_status IN ('APPROVED', 'PENDING', 'REJECTED'));
