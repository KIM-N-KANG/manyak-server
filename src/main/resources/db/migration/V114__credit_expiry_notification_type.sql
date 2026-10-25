-- 회원 자산 소멸 안내를 기존 ACTIVITY 알림함에 기록한다.
ALTER TABLE user_notifications DROP CONSTRAINT user_notifications_type_check;
ALTER TABLE user_notifications ADD CONSTRAINT user_notifications_type_check
    CHECK (type IN ('STORY_COMPLETED', 'STORY_MODERATION_COMPLETED', 'NOTICE',
        'ATTENDANCE_REMINDER', 'CREDIT_EXPIRY_REMINDER', 'PROMOTION'));
