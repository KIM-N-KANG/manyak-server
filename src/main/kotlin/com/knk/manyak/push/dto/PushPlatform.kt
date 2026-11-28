package com.knk.manyak.push.dto

/** 지원 플랫폼. 값을 더하면 `device_push_tokens.platform` CHECK와 발송기의 플랫폼별 구성도 같이 바꾼다. */
enum class PushPlatform {
    ANDROID,
    WEB,
    IOS,
}
