-- 채팅 세션에 추측 불가능한 외부 식별자(public_id)를 추가한다.
-- 순차 PK(id) 열거로 타인의 채팅에 접근하는 IDOR를 막기 위해 API는 이 값만 입출력한다.
-- DEFAULT gen_random_uuid()로 기존 행 백필과 NOT NULL을 단일 문으로 처리하고,
-- DB 직접 삽입(SQL 테스트·타 시스템 연동) 시에도 식별자 누락을 막는 안전망을 둔다.
-- (애플리케이션은 엔티티에서 UUID.randomUUID()로 동일한 v4 UUID를 직접 채운다.)
ALTER TABLE story_play_sessions
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE story_play_sessions
    ADD CONSTRAINT uq_story_play_sessions_public_id UNIQUE (public_id);
