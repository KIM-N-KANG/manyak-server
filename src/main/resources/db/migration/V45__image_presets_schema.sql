-- KNK-409: 팀 제작 이미지 카탈로그와 스토리 썸네일 컬럼 (스펙 §4-3-9 · §4-4, 간극표 B10).
--
-- 설계 메모
-- * image_key가 런타임 정본이다. 서빙 URL은 백엔드가 {base}/{prefix}/{image_key}.png로 조합하므로 URL을 저장하지 않는다.
-- * image_key는 불변이고 이미지 교체는 새 키 발급이다. 저장된 지난 턴이 언제 봐도 같아야 하기 때문이다.
-- * 행 삭제 금지. 운영 제외는 deactivated_at 기록으로만 한다(활성 여부는 이 컬럼의 파생).
--   비활성을 불리언이 아니라 시각으로 두는 이유: 지난 턴 images[] 재구성이 "그 턴의 확정 시각 시점에 활성이었나"를
--   판정해야 completed 응답과 결과가 같아진다(§4-3-9 재구성 불변). 불리언이면 이 비교가 불가능하다.
-- * created_at(등록 시각)도 같은 재구성 판정에 쓰인다 — 확정 시각 이후 등록된 키는 소급 유효화되면 안 된다.
-- * 의미 태그 3축의 뜻은 타입마다 다르다: mood = 분위기(THUMBNAIL·BACKGROUND) 또는 성격(CHARACTER),
--   subject = 장소(THUMBNAIL·BACKGROUND) 또는 성별(CHARACTER), prop = 소품(공통).
--   썸네일 자동 연결은 장르만 쓰고, 나머지 축은 채팅 이미지(KNK-544)에서 AI에 전달할 후보 메타다.

CREATE TABLE image_presets (
    id BIGSERIAL PRIMARY KEY,
    image_key VARCHAR(64) NOT NULL,
    type VARCHAR(20) NOT NULL,
    mood VARCHAR(50),
    subject VARCHAR(50),
    prop VARCHAR(50),
    deactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_image_presets_image_key UNIQUE (image_key),
    CONSTRAINT ck_image_presets_type
        CHECK (type IN ('THUMBNAIL', 'BACKGROUND', 'CHARACTER')),
    -- 마커 추출 정규식(\[\[image:[a-z0-9_]{1,64}\]\])이 안전하려면 키 문자 집합이 좁아야 한다.
    -- 한글·공백이 S3 객체 키·CDN 캐시 키·AI 마커에 실리는 위험도 함께 없앤다.
    CONSTRAINT ck_image_presets_image_key_format
        CHECK (image_key ~ '^[a-z0-9_]{1,64}$')
);

CREATE INDEX idx_image_presets_type_active ON image_presets (type, deactivated_at);

-- 장르는 복수(썸네일은 최대 3개)라 조인 테이블로 둔다.
-- 태그 마스터에 FK를 걸어 "GENRE 마스터 태그명과 정확 일치"를 DB가 강제한다 —
-- 시드가 마스터에 없는 장르를 넣으려 하면 스칼라 서브쿼리가 NULL이 되어 NOT NULL 위반으로 시드 전체가 롤백된다.
CREATE TABLE image_preset_genres (
    image_preset_id BIGINT NOT NULL REFERENCES image_presets (id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES story_creation_tags (id),

    PRIMARY KEY (image_preset_id, tag_id)
);

CREATE INDEX idx_image_preset_genres_tag ON image_preset_genres (tag_id);

-- 스토리 대표 이미지(표지). 등록 시 1회 확정하고, 이후 수정으로 장르가 바뀌어도 재연결하지 않는다.
-- 기존 스토리는 백필하지 않고 NULL로 둔다(프론트엔드가 placeholder 표시).
-- image_key FK는 카탈로그 행 삭제 금지 불변을 DB로 보강한다.
ALTER TABLE stories ADD COLUMN thumbnail_image_key VARCHAR(64);

ALTER TABLE stories
    ADD CONSTRAINT fk_stories_thumbnail_image_key
        FOREIGN KEY (thumbnail_image_key) REFERENCES image_presets (image_key);
