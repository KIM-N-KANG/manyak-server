package com.knk.manyak.auth.social

import java.time.Instant

/**
 * 소셜 제공자(Google·Kakao)가 검증된 ID 토큰에서 내려주는 사용자 식별·프로필 정보.
 *
 * - [providerUserId]: 제공자가 발급한 안정적 사용자 식별자(`sub`). (provider, providerUserId)가 계정 유일성을 보장한다.
 * - [email]/[name]/[picture]: 선택 클레임. 제공자나 사용자 설정에 따라 없을 수 있어 모두 nullable이다.
 * - [issuedAt]: 토큰 발급 시각(`iat`). 계정 연동의 재인증 신선도 판정에 쓴다(KNK-739) — 오래전에 받아 둔
 *   ID 토큰을 재제출하면 재인증이 형식만 남기 때문이다. 로그인 경로는 이 값을 쓰지 않는다.
 */
data class SocialUserInfo(
    val providerUserId: String,
    val email: String? = null,
    val name: String? = null,
    val picture: String? = null,
    val issuedAt: Instant? = null,
)
