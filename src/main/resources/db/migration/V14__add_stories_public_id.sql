-- 스토리에 추측 불가능한 외부 식별자(public_id)를 추가한다.
-- 순차 PK(id) 열거로 타인의 스토리를 조회·삭제하는 IDOR를 막기 위해 API는 이 값만 입출력한다(채팅 KNK-178 선례).
-- 1) 컬럼 추가(우선 nullable) → 2) 기존 행 백필 → 3) NOT NULL + UNIQUE 승격.
ALTER TABLE stories ADD COLUMN public_id UUID;

UPDATE stories SET public_id = gen_random_uuid() WHERE public_id IS NULL;

ALTER TABLE stories ALTER COLUMN public_id SET NOT NULL;

ALTER TABLE stories
    ADD CONSTRAINT uq_stories_public_id UNIQUE (public_id);
