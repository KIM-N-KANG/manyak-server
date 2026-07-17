-- KNK-544: 채팅 이미지 런타임 재료 (스펙 §4-3-9 · §4-4).
--
-- 설계 메모
-- * 배경과 캐릭터는 확정 시점이 다르다. 배경 후보는 스토리 등록 시 장르 매칭으로 고정하고(story_images)
--   매 턴 같은 목록을 AI에 전달해 AI가 고른다. 인물↔이미지는 간편 제작 컴파일이 1회 확정한다(story_characters).
--   후보를 등록 시 확정하는 이유: 백엔드는 이 턴의 장소·분위기를 모르고(의미 판단은 AI 몫), 매 턴 동일 목록이
--   프롬프트 prefix를 안정시켜 캐싱에 유리하며, AI 무상태가 유지된다.
-- * 두 테이블 모두 이름·태그를 복제하지 않는다. 의미 태그의 정본은 image_presets 한 곳이며 여기는 얇은 연결이다.
--   덕분에 후속에 사용자 업로드 자산이 도입돼도 image_key만 바꿔 끼우면 되고 스키마가 갈라지지 않는다.
-- * image_key FK가 카탈로그 행 삭제 금지 불변을 DB로 보강한다(운영 제외는 image_presets.deactivated_at).

-- 스토리↔배경 후보. 등록 시 최대 8장 확정, 매 턴 AI 요청에 동일 목록 전달(비활성은 전달 시 제외).
CREATE TABLE story_images (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories (id) ON DELETE CASCADE,
    image_key VARCHAR(64) NOT NULL REFERENCES image_presets (image_key),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_images_story_image UNIQUE (story_id, image_key)
);

CREATE INDEX idx_story_images_story ON story_images (story_id, id);

-- 인물↔이미지 매핑(컴파일 산출물 — 주요 사건·엔딩과 같은 저장 패턴).
-- image_key는 nullable: 후보 밖 키 무효화·배정 실패 인물은 이미지 없이 진행한다(graceful).
-- 이름은 스토리 안에서 유일하다 — 매 턴 요청에 "인물 이름 = imageKey"로 실어 보내므로 중복이면 지목이 모호해진다.
CREATE TABLE story_characters (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories (id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    image_key VARCHAR(64) REFERENCES image_presets (image_key),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_characters_story_name UNIQUE (story_id, name)
);

CREATE INDEX idx_story_characters_story ON story_characters (story_id, id);

-- 본문 확정 시각 — 지난 턴 images[] 재구성의 컷오프 앵커(스펙 §4-3-9 재구성 불변).
--
-- 재구성은 "그 턴이 확정된 시점의 카탈로그 상태"를 봐야 completed 응답과 결과가 같아진다:
--   등록 시각 <= 확정 시각 AND (deactivated_at IS NULL OR deactivated_at > 확정 시각)
--
-- 앵커가 created_at이 아니라 확정 시각인 이유: 재생성은 그 시점 카탈로그로 재검증하므로, 원 생성 시각으로
-- 자르면 재생성 completed와 재구성이 어긋난다. 현행 재생성 저장은 본문만 제자리 교체하고 타임스탬프를
-- 건드리지 않으므로(story_messages는 created_at뿐) 컬럼을 새로 두고 재생성 트랜잭션에서 갱신한다.
ALTER TABLE story_messages ADD COLUMN content_confirmed_at TIMESTAMPTZ;

-- 기존 행은 최초 생성이 곧 확정이다.
UPDATE story_messages SET content_confirmed_at = created_at WHERE content_confirmed_at IS NULL;

ALTER TABLE story_messages ALTER COLUMN content_confirmed_at SET NOT NULL;
ALTER TABLE story_messages ALTER COLUMN content_confirmed_at SET DEFAULT now();
