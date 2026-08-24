-- KNK-966: 컴파일 산출물의 인물별 외형·이미지를 스토리에 고정 저장한다(스펙 §4-4, §5-3-3).
-- 같은 인물 = 같은 이미지를 DB 고정으로 보장하고, 외형 7필드는 썸네일 생성·이미지 재생성 재료로 남긴다.

CREATE TABLE story_characters (
    id BIGSERIAL PRIMARY KEY,
    -- 외부 노출 식별자. 순차 PK를 API에 노출하지 않는다(IDOR 방지, stories와 같은 관례).
    public_id UUID NOT NULL UNIQUE,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    -- 인물 식별자이자 컴파일 응답의 character_appearances[]·character_images[] 매칭 키.
    name VARCHAR(100) NOT NULL,
    -- 이미지 생성·업로드 실패 시 NULL. 실패가 스토리 생성을 막지 않는다(graceful).
    image_url TEXT,
    -- 외형 7필드. LLM이 못 채운 칸은 NULL이며, 항목 자체는 인물 전원에 대해 존재한다.
    gender TEXT,
    age TEXT,
    body TEXT,
    face TEXT,
    hair TEXT,
    outfit TEXT,
    visual_identity TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 이름이 매칭 키라 스토리 안에서 유일해야 한다. story_id 선두라 스토리별 조회 인덱스도 겸한다.
    CONSTRAINT uq_story_characters_name UNIQUE (story_id, name)
);
