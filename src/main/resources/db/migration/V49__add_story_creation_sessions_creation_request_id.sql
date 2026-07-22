-- KNK-644: 게스트 세션 생성 크래시 복구.
-- 완성 시점의 request_id를 세션에 저장해, 회수 재실행(KNK-631/§4-3-8)이 "이 세션을 만든 그 요청"인지 검증한다.
-- 익명(게스트) 세션은 소유자가 없어 이 바인딩이 없으면 회수를 정당한 요청자에 묶을 수 없다(KNK-635 Codex P1 → 회원 전용 제한).
-- 이 컬럼으로 게스트 세션도 안전하게 재구성 복구한다. 기존 행은 NULL(회수 대상 아님)이 맞다.
ALTER TABLE story_creation_sessions
    ADD COLUMN creation_request_id UUID;
