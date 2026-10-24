-- 기존 회차의 딥링크 유무를 현재 스토리/공지 상태로 추측해 채우지 않는다.
ALTER TABLE push_campaigns ADD COLUMN deep_link_included BOOLEAN;
