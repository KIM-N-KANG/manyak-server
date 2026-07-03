-- ai_call_logs의 턴 번호 컬럼을 분석 표준(turn_number)에 맞춰 rename한다(스펙 간극 B3).
-- 6-analytics.md는 ai_call_logs 컬럼·구조화 로그(user_message_saved·ai_response_saved)를 이미 turn_number로 문서화했다.
ALTER TABLE ai_call_logs RENAME COLUMN turn_index TO turn_number;
ALTER TABLE ai_call_logs RENAME CONSTRAINT ck_ai_call_logs_turn_index TO ck_ai_call_logs_turn_number;
