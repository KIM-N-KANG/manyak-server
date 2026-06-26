package com.knk.manyak.auth.social

/**
 * 소셜 제공자(현재 Google)가 검증된 ID 토큰에서 내려주는 사용자 식별·프로필 정보.
 *
 * - [providerUserId]: 제공자가 발급한 안정적 사용자 식별자(Google의 `sub`). (provider, providerUserId)가 계정 유일성을 보장한다.
 * - [email]/[name]/[picture]: 선택 클레임. 제공자나 사용자 설정에 따라 없을 수 있어 모두 nullable이다.
 */
data class SocialUserInfo(
    val providerUserId: String,
    val email: String? = null,
    val name: String? = null,
    val picture: String? = null,
)
