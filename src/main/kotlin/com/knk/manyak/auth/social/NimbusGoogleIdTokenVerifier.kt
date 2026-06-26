package com.knk.manyak.auth.social

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Google ID 토큰을 [NimbusJwtDecoder]로 검증하는 [GoogleIdTokenVerifier] 구현.
 *
 * 검증 항목:
 * - 서명: Google JWK Set(`https://www.googleapis.com/oauth2/v3/certs`)의 공개키로 RS256 서명을 검증한다.
 * - 기본: 만료(exp)/nbf 등 [JwtValidators.createDefault].
 * - issuer(iss): `https://accounts.google.com` 또는 `accounts.google.com` 둘 다 허용한다(Google이 두 형식을 쓴다).
 * - audience(aud): 설정된 client-id([GoogleAuthProperties.clientIds]) 중 하나를 포함해야 한다(토큰 오용 차단).
 *
 * 검증 실패(서명·만료·issuer·audience·형식 오류)는 모두 401([ResponseStatusException])로 통일한다.
 * 클레임 추출: `sub`→providerUserId, `email`/`name`/`picture`.
 *
 * 디코더를 생성자로 주입받는다(운영은 [Component] 보조 생성자가 JWK URI 디코더를 만들고,
 * 테스트는 자체 공개키 디코더를 주입해 Google 호출 없이 검증 규칙만 격리한다).
 */
@Component
class NimbusGoogleIdTokenVerifier(
    properties: GoogleAuthProperties,
    private val decoder: NimbusJwtDecoder,
) : GoogleIdTokenVerifier {

    init {
        // 기본 클레임 변환은 iss를 URL로 강제 변환한다. Google이 쓰는 무스킴 형식("accounts.google.com")은
        // URL이 아니라 decode 단계에서 IllegalArgumentException이 난다. iss를 문자열로 유지하도록 변환을 덮어쓴다.
        val keepIssAsString = Converter<Any, Any> { it.toString() }
        decoder.setClaimSetConverter(
            MappedJwtClaimSetConverter.withDefaults(mapOf(JwtClaimNames.ISS to keepIssAsString)),
        )
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefault(),
                GoogleIssuerValidator(),
                AudienceValidator(properties.clientIds),
            ),
        )
    }

    /** 운영용: Google JWK Set URI로 서명 검증 디코더를 만든다. (Spring이 이 생성자로 빈을 만든다.) */
    @Autowired
    constructor(properties: GoogleAuthProperties) : this(
        properties,
        NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI).build(),
    )

    override fun verify(idToken: String): SocialUserInfo {
        val jwt: Jwt = try {
            decoder.decode(idToken)
        } catch (ex: JwtException) {
            throw unauthorized(ex)
        }
        return SocialUserInfo(
            providerUserId = jwt.subject
                ?: throw unauthorized(null),
            email = jwt.getClaimAsString("email"),
            name = jwt.getClaimAsString("name"),
            picture = jwt.getClaimAsString("picture"),
        )
    }

    private fun unauthorized(cause: Throwable?): ResponseStatusException =
        ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Google ID 토큰입니다.", cause)

    /**
     * Google이 발급하는 iss 두 형식(https 유무)을 모두 허용한다.
     * iss는 URL이 아닐 수 있어(무스킴 형식) 문자열 클레임으로 직접 읽는다(getIssuer()의 URL 변환 회피).
     */
    private class GoogleIssuerValidator : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (token.getClaimAsString(JwtClaimNames.ISS) in ALLOWED_ISSUERS) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error("invalid_issuer", "허용되지 않은 issuer입니다.", null),
                )
            }

        private companion object {
            val ALLOWED_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        }
    }

    /** aud가 설정된 client-id 중 하나를 포함해야 통과시킨다(빈 설정이면 항상 실패). */
    private class AudienceValidator(private val clientIds: List<String>) : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (token.audience.any { it in clientIds }) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error("invalid_audience", "허용되지 않은 audience입니다.", null),
                )
            }
    }

    private companion object {
        const val GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs"
    }
}
