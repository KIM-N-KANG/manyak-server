package com.knk.manyak.auth.config

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.social.GoogleAuthProperties
import com.knk.manyak.auth.social.KakaoAuthProperties
import com.knk.manyak.auth.social.NimbusSocialIdTokenVerifier
import com.knk.manyak.auth.social.SocialIdTokenVerifier
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * 인증 설정 바인딩과 JWT 검증·소셜 ID 토큰 검증 빈을 등록한다.
 *
 * - [AuthProperties](`manyak.auth.jwt`)·[GoogleAuthProperties](`manyak.auth.google`)·
 *   [KakaoAuthProperties](`manyak.auth.kakao`)를 활성화(@EnableConfigurationProperties)한다.
 * - 리소스 서버가 Bearer 토큰을 검증할 때 쓰는 [JwtDecoder]를 [JwtTokenProvider](발급과 같은 secret)로 노출한다.
 * - 소셜 검증기는 provider별로 한 개씩 만들어 [socialIdTokenVerifiers] 맵으로 묶는다. 검증 파라미터(JWK Set URI·issuer)는
 *   스펙 §4-5 표의 고정값이며, discovery 문서를 런타임에 조회하지 않는다(외부 의존 추가 없음).
 */
@Configuration
@EnableConfigurationProperties(
    AuthProperties::class,
    GoogleAuthProperties::class,
    KakaoAuthProperties::class,
)
class AuthConfig {

    @Bean
    fun jwtDecoder(jwtTokenProvider: JwtTokenProvider): JwtDecoder = jwtTokenProvider.jwtDecoder()

    @Bean
    fun googleIdTokenVerifier(properties: GoogleAuthProperties): SocialIdTokenVerifier =
        NimbusSocialIdTokenVerifier(
            SocialProvider.GOOGLE,
            GOOGLE_JWK_SET_URI,
            GOOGLE_ISSUERS,
            properties.clientIds,
        )

    @Bean
    fun kakaoIdTokenVerifier(properties: KakaoAuthProperties): SocialIdTokenVerifier =
        NimbusSocialIdTokenVerifier(
            SocialProvider.KAKAO,
            KAKAO_JWK_SET_URI,
            KAKAO_ISSUERS,
            properties.clientIds,
        )

    /**
     * provider → 검증기. 항목이 없는 provider(APPLE·NAVER는 enum에만 예약)는 로그인이 열리지 않는다(fail-closed).
     * 로그인 서비스가 이 맵으로만 검증기를 찾으므로, 새 provider 추가는 여기에 항목을 더하는 일이 된다.
     */
    @Bean
    fun socialIdTokenVerifiers(
        @Qualifier("googleIdTokenVerifier") google: SocialIdTokenVerifier,
        @Qualifier("kakaoIdTokenVerifier") kakao: SocialIdTokenVerifier,
    ): Map<SocialProvider, SocialIdTokenVerifier> = mapOf(
        SocialProvider.GOOGLE to google,
        SocialProvider.KAKAO to kakao,
    )

    companion object {
        // 스펙 §4-5 검증 파라미터 표. 값 오타는 해당 provider 로그인 전면 실패로 이어지므로 테스트가 리터럴로 고정한다.
        const val GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs"

        /** Google은 iss를 https 유무 두 형식으로 발급한다. */
        val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")

        const val KAKAO_JWK_SET_URI = "https://kauth.kakao.com/.well-known/jwks.json"
        val KAKAO_ISSUERS = setOf("https://kauth.kakao.com")
    }
}
