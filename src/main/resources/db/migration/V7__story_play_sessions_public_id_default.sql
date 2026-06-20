-- public_id에 DB 레벨 기본값(DEFAULT gen_random_uuid())을 부여한다.
-- 애플리케이션은 엔티티에서 UUID.randomUUID()로 채우지만, DB 직접 삽입(SQL 테스트·타 시스템 연동) 시
-- 식별자 누락을 막는 안전망으로 둔다. (KNK-178 리뷰 반영 — 이미 적용된 V6를 수정하는 대신 별도 마이그레이션으로 분리)
ALTER TABLE story_play_sessions ALTER COLUMN public_id SET DEFAULT gen_random_uuid();
