-- 기존 알림 문구는 추측해 채우지 않는다. 첫 알림 게시 전에 관리자가 입력한다.
ALTER TABLE notices
    ADD COLUMN notification_title VARCHAR(100),
    ADD COLUMN notification_body VARCHAR(300),
    ADD CONSTRAINT ck_notices_notification_text_pair CHECK (
        (notification_title IS NULL AND notification_body IS NULL)
        OR (notification_title IS NOT NULL AND notification_body IS NOT NULL)
    );
