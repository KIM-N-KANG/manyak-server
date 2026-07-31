package com.knk.manyak.auth.social

import com.knk.manyak.auth.entity.SocialProvider
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
import org.springframework.web.server.ResponseStatusException

/**
 * 소셜 ID 토큰을 [NimbusJwtDecoder]로 검증하는 [SocialIdTokenVerifier] 구현.
 *
 * Google·Kakao 모두 OIDC ID 토큰(RS256)이라 검증 절차가 같고 파라미터만 다르다(스펙 §4-5).
 * 그래서 provider별 값(JWK Set URI·허용 issuer·client-id 목록)을 주입받는 한 벌의 구현으로 둔다.
 *
 * 검증 항목:
 * - 서명: provider JWK Set의 공개키로 RS256 서명을 검증한다.
 * - 기본: 만료(exp)/nbf 등 [JwtValidators.createDefault].
 * - issuer(iss): [allowedIssuers] 중 하나여야 한다(Google은 https 유무 두 형식, Kakao는 https 단일).
 * - audience(aud): 설정된 client-id 중 하나를 포함해야 한다(토큰 오용 차단). **목록이 비면 전부 거부한다(fail-closed).**
 *   특히 Kakao는 `sub`가 앱별(pairwise)이라, 이 검사가 다른 앱이 발급한 같은 `sub`를 같은 계정으로 수렴시키지 않는 유일한 방어선이다.
 *
 * 검증 실패(서명·만료·issuer·audience·형식 오류)는 모두 401([ResponseStatusException])로 통일한다.
 * 클레임 추출: `sub`→providerUserId, `email`/`name`/`picture`(Kakao는 동의항목을 요청하지 않아 셋 다 오지 않는다).
 *
 * 디코더를 생성자로 주입받는다(운영은 JWK URI 보조 생성자,
 * 테스트는 자체 공개키 디코더를 주입해 provider 호출 없이 검증 규칙만 격리한다).
 */
class NimbusSocialIdTokenVerifier(
    private val provider: SocialProvider,
    allowedIssuers: Set<String>,
    clientIds: List<String>,
    private val decoder: NimbusJwtDecoder,
) : SocialIdTokenVerifier {

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
                IssuerValidator(allowedIssuers),
                AudienceValidator(clientIds),
            ),
        )
    }

    /** 운영용: provider JWK Set URI로 서명 검증 디코더를 만든다. */
    constructor(
        provider: SocialProvider,
        jwkSetUri: String,
        allowedIssuers: Set<String>,
        clientIds: List<String>,
    ) : this(provider, allowedIssuers, clientIds, NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build())

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
            // 계정 연동의 재인증 신선도 판정용(KNK-739). 로그인 경로는 쓰지 않는다.
            issuedAt = jwt.issuedAt,
        )
    }

    private fun unauthorized(cause: Throwable?): ResponseStatusException {
        val label = provider.name.lowercase().replaceFirstChar(Char::uppercase)
        return ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 $label ID 토큰입니다.", cause)
    }

    /**
     * provider가 발급하는 iss 형식만 허용한다(Google은 https 유무 두 형식을 쓴다).
     * iss는 URL이 아닐 수 있어(무스킴 형식) 문자열 클레임으로 직접 읽는다(getIssuer()의 URL 변환 회피).
     */
    private class IssuerValidator(private val allowedIssuers: Set<String>) : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (token.getClaimAsString(JwtClaimNames.ISS) in allowedIssuers) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error("invalid_issuer", "허용되지 않은 issuer입니다.", null),
                )
            }
    }

    /**
     * aud가 설정된 client-id 중 하나를 포함해야 통과시킨다(빈 설정이면 항상 실패).
     *
     * 여기에 `azp`(authorized party) 검사를 더한다(KNK-739). OIDC Core는 **aud가 여러 개면 `azp`를 요구**하고,
     * `azp`가 있으면 그 값이 우리 client-id여야 한다. 이게 없으면 남의 앱이 우리 client-id를 audience로 지정해
     * 받아낸 토큰이 통과할 수 있는데, 계정 연동은 계정 소유권을 바꾸는 경로라 그 구멍을 열어 둘 수 없다.
     * 카카오처럼 `azp`를 싣지 않는 단일 audience 토큰은 종전대로 통과한다.
     *
     * **운영 주의**: 네이티브 앱이 서버 client-id를 audience로 요청하면 `azp`에 그 앱의 client-id가 실린다.
     * 그런 구성을 도입하면 해당 client-id도 `MANYAK_*_CLIENT_IDS`에 함께 넣어야 로그인이 막히지 않는다.
     */
    private class AudienceValidator(private val clientIds: List<String>) : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult {
            if (token.audience.none { it in clientIds }) {
                return failure("허용되지 않은 audience입니다.")
            }
            val authorizedParty = token.getClaimAsString(CLAIM_AZP)
            if (token.audience.size > 1 && authorizedParty == null) {
                return failure("audience가 여러 개면 azp가 필요합니다.")
            }
            if (authorizedParty != null && authorizedParty !in clientIds) {
                return failure("허용되지 않은 azp입니다.")
            }
            return OAuth2TokenValidatorResult.success()
        }

        private fun failure(description: String) =
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_audience", description, null))

        private companion object {
            const val CLAIM_AZP = "azp"
        }
    }
}
