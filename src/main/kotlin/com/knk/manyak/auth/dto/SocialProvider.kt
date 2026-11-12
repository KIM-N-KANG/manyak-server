package com.knk.manyak.auth.dto

enum class SocialProvider {
    GOOGLE,
    KAKAO,
    APPLE,
    NAVER,
}

/**
 * 외부 계약(API 응답·분석 이벤트 프로퍼티)에 싣는 표기. **소문자 고정**이다(KNK-739).
 * 프론트엔드 경로와 NextAuth provider ID가 소문자라, enum 직렬화(대문자)를 그대로 쓰면 계약이 갈린다.
 */
val SocialProvider.wireValue: String
    get() = name.lowercase()
