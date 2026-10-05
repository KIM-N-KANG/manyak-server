-- 컴파일에서 받은 주변 인물 소개. 기존 스토리는 백필하지 않는다(KNK-1480).
ALTER TABLE story_characters ADD COLUMN description TEXT;

COMMENT ON COLUMN story_characters.description IS '스토리 상세용 주변 인물 소개. 소개가 없으면 NULL';
