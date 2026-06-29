-- KNK-317: 분석 상관관계 식별자를 device_id로 통일한다.
-- ai_call_logs.anonymous_id_hash 컬럼을 device_id_hash로 리네임(앱 엔티티·MDC 키·스펙 AN-3와 일치).
ALTER TABLE ai_call_logs RENAME COLUMN anonymous_id_hash TO device_id_hash;

-- 기존 행의 해시 prefix(anon_hash_)도 device_hash_로 정규화해, 앱이 새로 쓰는 prefix와 통일한다
-- (같은 digest가 prefix 차이로 분석에서 갈라지지 않게). 현재 운영 데이터는 모두 NULL이라 영향 없음.
UPDATE ai_call_logs SET device_id_hash = REPLACE(device_id_hash, 'anon_hash_', 'device_hash_') WHERE device_id_hash LIKE 'anon_hash_%';
