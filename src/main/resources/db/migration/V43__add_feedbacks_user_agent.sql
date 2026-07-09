-- platform enum(IOS/ANDROID/WEB)만으로는 세분화에 한계가 있어(스펙 4-3-4 Phase 1 계획),
-- 서버가 요청의 User-Agent 헤더 원문을 함께 저장해 클라이언트 수정 없이 OS·브라우저 수준으로 세분 수집한다.
-- 클라이언트 제보 body 가 아니라 요청 헤더에서 서버가 채우는 값이라 nullable 이다.
ALTER TABLE feedbacks ADD COLUMN user_agent VARCHAR(512);
