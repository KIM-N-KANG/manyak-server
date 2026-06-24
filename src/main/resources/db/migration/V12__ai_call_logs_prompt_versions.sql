-- AI 응답 meta의 프롬프트 버전(레이어 키→버전 맵)을 적재할 JSONB 컬럼을 신설한다.
-- 단일 스칼라 prompt_template_version VARCHAR(40)으로는 chat의 다중 키(SAFETY/CORE/STORY/CHARACTER/USER/MEMORY)를
-- 담을 수 없으므로, AI가 보낸 객체를 그대로 보관하는 JSONB로 받는다(레이어 키 변환 없음).
-- 기존 prompt_template_version은 호환을 위해 병존시키고 본 마이그레이션에서 제거하지 않는다.
ALTER TABLE ai_call_logs ADD COLUMN prompt_versions JSONB;
