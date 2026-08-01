-- ai_call_logs 내보내기 — Langfuse trace ↔ 백엔드 기록 대조용 (KNK-751 / KNK-707)
--
-- 왜 필요한가
--   AI 서버(manyak-ai)가 Langfuse에 남기는 trace에는 "이 호출이 실제로 DB에 저장까지 됐는지", "몇 번째 턴이었는지"가
--   없다. 백엔드만 그 결과를 안다. 반대로 백엔드에는 프롬프트·응답 원문이 없다(§6·§8 원문 비수집).
--   두 쪽을 request_id로 조인하면 "AI는 응답했는데 저장이 안 된 호출", "턴 번호가 비어 있는 구간(누락 신호)" 같은
--   질문에 답할 수 있다. 이 파일은 그 조인용 최소 컬럼을 뽑는 쿼리다.
--
-- 파이프라인 사용법
--   1) request_id 로 Langfuse trace와 1:1 조인한다.
--      백엔드가 AI 호출 시 X-Manyak-Request-Id 헤더로 같은 값을 넘기고(CorrelationHeaders), AI가 그대로 trace에
--      기록한다. ai_call_logs 한 행 = AI 호출 한 번 = Langfuse trace 하나.
--   2) chat_id + turn_number 로 논리적인 "턴"을 식별한다.
--      한 턴에 AI 호출이 여러 번 붙을 수 있다(본문 생성 chat_response + 선택지 choice_generation, 재생성 시 추가).
--      그래서 trace 하나가 턴 하나가 아니다. 턴 단위로 묶으려면 이 두 컬럼을 키로 써야 한다.
--   3) turn_persisted 로 저장 성공 여부를 판정한다(아래 주의 참고).
--
-- 컬럼
--   request_id     AI 호출 상관관계 ID. Langfuse trace와의 조인 키. 재시도해도 같은 요청이면 같은 값이다.
--   feature        어떤 AI 호출인지. DB 값은 소문자 snake_case다(아래 주의 참고).
--   story_public_id  스토리의 외부 공개 UUID. ai_call_logs.story_id는 내부 PK(BIGINT)라 stories 조인이 필요하다.
--                    스토리라인 생성 단계는 스토리가 아직 없어 NULL이다.
--   chat_id        채팅의 외부 공개 UUID. ai_call_logs가 이미 public_id를 들고 있어 조인이 필요 없다.
--                  채팅 외 호출(스토리 제작)은 NULL이다.
--   turn_number    저장이 확정한 최종 턴 번호(1-based). 저장 전이면 NULL.
--   turn_persisted 턴이 DB에 확정 저장됐는지(true/false). status가 아니라 turn_number 유무로 판정한다.
--   status         AI **호출** 결과(STARTED / SUCCEEDED / FAILED). 저장 성공과 다른 축이다.
--   created_at     호출 시작 시각. 증분 추출 구간을 자르는 데 쓴다(idx_ai_call_logs_created_at).
--
-- ⚠️ 주의 1 — 저장 성공은 status가 아니라 turn_number로 판정한다
--   status = 'SUCCEEDED'는 **AI 호출이 성공했다**는 뜻일 뿐이다. 그 뒤 DB 저장이 실패하거나(트랜잭션 오류,
--   재생성 중 마지막 턴이 바뀜) SSE 전송이 끊겨도 status는 SUCCEEDED로 남는다.
--   반면 turn_number는 AiCallRecorder.attachTurnNumber가 채우는데, 이 호출은 ChatTurnPersister가 턴을 DB에
--   확정 저장한 **뒤에만** 실행된다(ChatService.streamTurnInternal: persist 성공 → attachTurnNumber).
--   따라서 turn_number IS NOT NULL 이 "저장까지 성사됨"의 정확한 신호다.
--   → status='SUCCEEDED' AND turn_persisted=false 인 행이 바로 "AI는 응답했는데 저장은 안 된" 호출이다.
--
-- ⚠️ 주의 2 — feature 값은 소문자다
--   코드의 enum 이름(CHAT_RESPONSE 등)과 DB 저장값이 다르다. AiCallFeatureConverter가 소문자 snake_case로
--   변환해 넣고, ck_ai_call_logs_feature CHECK가 아래 4개만 허용한다. 필터링할 때 대문자로 쓰면 0행이 나온다.
--     chat_response         ← CHAT_RESPONSE        채팅 턴 본문 생성(SSE)
--     choice_generation     ← CHOICE_GENERATION    다음 행동 선택지 3개 생성
--     storyline_generation  ← STORYLINE_GENERATION 간편 제작 스토리라인 후보 생성
--     story_completion      ← STORY_COMPLETION     간편 제작 스토리 완성(compile)
--   turn_number는 chat_response·choice_generation에만 있다(스토리 제작에는 턴 개념이 없어 항상 NULL).
--
-- 실행
--   psql "$MANYAK_DB_URL" -f docs/ai-call-logs-export.sql
--   CSV로 뽑으려면: psql "$MANYAK_DB_URL" --csv -f docs/ai-call-logs-export.sql > ai_call_logs.csv
--   추출 구간은 아래 WHERE의 interval을 바꾼다(기본 최근 7일).

SELECT
    l.request_id,
    l.feature,
    s.public_id AS story_public_id,
    l.chat_id,
    l.turn_number,
    -- 저장 성공 신호. status가 아니라 turn_number 유무다(위 주의 1).
    (l.turn_number IS NOT NULL) AS turn_persisted,
    l.status,
    l.created_at
FROM ai_call_logs l
    -- story_id는 내부 PK(BIGINT)라 공개 UUID를 내보내려면 조인해야 한다.
    -- LEFT JOIN인 이유: 스토리라인 생성 단계에는 스토리가 아직 없고, 스토리가 하드 삭제돼도 호출 이력은 남겨야 한다.
    LEFT JOIN stories s ON s.id = l.story_id
WHERE l.created_at >= now() - interval '7 days'
ORDER BY l.created_at DESC;
