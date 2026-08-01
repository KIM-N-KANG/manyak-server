package com.knk.manyak.auth.controller

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.social.SocialIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

/**
 * 소셜 로그인 통합 테스트용 가짜 배선. Google·Kakao 로그인 테스트가 **같은 Spring 컨텍스트를 공유**하도록
 * 테스트 클래스마다 중첩 @TestConfiguration을 두지 않고 이 클래스를 @Import한다(컨텍스트 캐시 절약 — KNK-686 배경).
 *
 * - provider 검증기 맵을 통째로 대체해 외부 IO(JWK Set 조회) 없이 로그인 경로만 검증한다.
 *   "invalid" 토큰은 401, 그 외에는 토큰 문자열을 그대로 sub로 삼는다(테스트가 sub를 직접 지정할 수 있다).
 * - 발급 시각(`issuedAt`)은 기본이 "방금"이다. 계정 연동의 재인증 신선도 검사를 red로 만들려면
 *   `stale:{sub}` 접두를 쓰면 오래전에 발급된 토큰이 된다(KNK-739).
 * - refresh 저장은 Redis 인프라 없이 돌도록 [InMemoryRefreshTokenStore]로 대체한다.
 */
@TestConfiguration
class FakeSocialLoginConfig {

    @Bean
    @Primary
    fun fakeSocialIdTokenVerifiers(): Map<SocialProvider, SocialIdTokenVerifier> {
        val verifier = SocialIdTokenVerifier { idToken ->
            if (idToken == "invalid") {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 ID 토큰입니다.")
            }
            val stale = idToken.startsWith(STALE_PREFIX)
            SocialUserInfo(
                providerUserId = idToken.removePrefix(STALE_PREFIX),
                email = "user@example.com",
                name = SOCIAL_DISPLAY_NAME,
                picture = "https://example.com/p.png",
                issuedAt = if (stale) Instant.now().minus(Duration.ofHours(1)) else Instant.now(),
            )
        }
        return mapOf(
            SocialProvider.GOOGLE to verifier,
            SocialProvider.KAKAO to verifier,
        )
    }

    @Bean
    @Primary
    fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()

    companion object {
        /** 소셜이 준 표시명. 서버는 이 값을 닉네임으로 쓰지 않아야 한다(스펙 §4-5 — 랜덤 발급). */
        const val SOCIAL_DISPLAY_NAME = "테스터"

        /** 이 접두가 붙은 토큰은 오래전에 발급된 것으로 취급한다(재인증 신선도 검사용). */
        const val STALE_PREFIX = "stale:"
    }
}
