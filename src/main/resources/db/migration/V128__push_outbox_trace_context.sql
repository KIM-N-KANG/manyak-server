-- 요청이나 작업의 스팬 안에서 기록한 발송 요청의 기록 스팬 추적 헤더(W3C traceparent, tracestate)를 저장한다(KNK-2097, NOTIFICATION 3-2-7).
-- 스케줄러가 기록한 행은 비운다. 롤링 배포 중 옛 서버가 함께 돌아도 깨지지 않도록 NULL을 허용한다.
ALTER TABLE push_outbox
    ADD COLUMN trace_parent varchar(55),
    ADD COLUMN trace_state varchar(512);
