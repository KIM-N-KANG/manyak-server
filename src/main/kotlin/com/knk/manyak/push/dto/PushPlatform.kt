package com.knk.manyak.push.dto

/** 지원 플랫폼. iOS 앱이 생기면 enum·CHECK 제약과 함께 FCM 메시지의 ApnsConfig도 같이 추가한다. */
enum class PushPlatform {
    ANDROID,
    WEB,
}
