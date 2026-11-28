-- KNK-2171: iOS 앱의 FCM 토큰도 등록할 수 있도록 플랫폼 CHECK에 IOS를 추가한다.
ALTER TABLE device_push_tokens DROP CONSTRAINT ck_device_push_tokens_platform;
ALTER TABLE device_push_tokens ADD CONSTRAINT ck_device_push_tokens_platform
    CHECK (platform IN ('ANDROID', 'WEB', 'IOS'));
