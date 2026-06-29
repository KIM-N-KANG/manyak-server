-- KNK-317: 분석 상관관계 식별자를 device_id로 통일한다.
-- ai_call_logs.anonymous_id_hash 컬럼을 device_id_hash로 리네임(앱 엔티티·MDC 키·스펙 AN-3와 일치).
ALTER TABLE ai_call_logs RENAME COLUMN anonymous_id_hash TO device_id_hash;
