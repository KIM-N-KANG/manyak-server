ALTER TABLE stories
    ADD COLUMN taken_down_at timestamptz,
    ADD COLUMN takedown_reason varchar(30),
    ADD COLUMN takedown_reason_detail varchar(100),
    ADD CONSTRAINT stories_takedown_reason_check
        CHECK (takedown_reason IN ('SEXUAL_CONTENT', 'COPYRIGHT_INFRINGEMENT', 'HATE_SPEECH', 'ETC')),
    ADD CONSTRAINT stories_takedown_state_check CHECK (
        (taken_down_at IS NULL AND takedown_reason IS NULL)
        OR (taken_down_at IS NOT NULL AND takedown_reason IS NOT NULL AND visibility = 'PRIVATE')),
    ADD CONSTRAINT stories_takedown_detail_check CHECK (
        (takedown_reason IS NOT DISTINCT FROM 'ETC' AND takedown_reason_detail IS NOT NULL
            AND length(trim(takedown_reason_detail)) > 0)
        OR (takedown_reason IS DISTINCT FROM 'ETC' AND takedown_reason_detail IS NULL));

ALTER TABLE user_notifications DROP CONSTRAINT user_notifications_type_check;
ALTER TABLE user_notifications ADD CONSTRAINT user_notifications_type_check
    CHECK (type IN ('STORY_COMPLETED', 'STORY_MODERATION_COMPLETED', 'STORY_TAKEN_DOWN', 'NOTICE',
        'ATTENDANCE_REMINDER', 'CREDIT_EXPIRY_REMINDER', 'PROMOTION'));
