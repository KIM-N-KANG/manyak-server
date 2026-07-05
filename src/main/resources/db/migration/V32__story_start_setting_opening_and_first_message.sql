-- 일반 모드 시작설정 저작(KNK-460): 오프닝 장면·첫 AI 메시지를 사용자가 직접 저작·저장할 수 있게 컬럼을 추가한다.
-- 초안 단계 부분 저장을 위해 nullable로 둔다(발행 완결성 검증은 별도 범위).
ALTER TABLE story_start_settings ADD COLUMN opening_scene TEXT;
ALTER TABLE story_start_settings ADD COLUMN first_ai_message TEXT;
