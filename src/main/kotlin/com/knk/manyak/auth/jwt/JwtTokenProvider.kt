package com.knk.manyak.auth.jwt

import com.knk.manyak.admin.config.AdminTokenProperties
import com.knk.manyak.auth.config.AuthProperties
import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * access 토큰(HS256 JWT) 발급과, 검증용 [JwtDecoder] 생성을 담당한다.
 *
 * - 클레임: `sub`=user.publicId(UUID), `iss`=설정 issuer, `iat`/`exp`(짧은 TTL).
 * - 서명/검증은 같은 대칭키(secret)로 한다. 발급은 [NimbusJwtEncoder], 검증은 [NimbusJwtDecoder].
 * - refresh 토큰은 JWT가 아니라 불투명 랜덤 문자열이므로 여기서 다루지 않는다.
 *
 * 만료는 [issueAccessToken] 호출 시점([clock]) 기준 `iat` + accessTtl 로 계산한다.
 * [clock]은 만료 경계 테스트를 위해 주입 가능하게 둔다.
 */
@Component
class JwtTokenProvider(
    private val properties: AuthProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val adminProperties: AdminTokenProperties = AdminTokenProperties(),
) {

    private val secretKey: SecretKey = properties.secret.toByteArray(Charsets.UTF_8).let { bytes ->
        require(bytes.size >= 32) { "JWT 대칭키는 UTF-8 기준 32바이트 이상이어야 합니다." }
        SecretKeySpec(bytes, MAC_ALGORITHM.name)
    }

    private val encoder: JwtEncoder = NimbusJwtEncoder(ImmutableSecret(secretKey))

    /** publicId(UUID)를 sub로 담는 HS256 access JWT를 발급해 직렬화 문자열로 반환한다. */
    fun issueAccessToken(publicId: UUID): String {
        val issuedAt = clock.instant()
        val expiresAt = issuedAt.plus(properties.accessTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .subject(publicId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build()
        val header = JwsHeader.with(MAC_ALGORITHM).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    /** access TTL의 초 단위 값. TokenResponse.expiresIn 으로 노출한다. */
    fun accessTtlSeconds(): Long = properties.accessTtl.seconds

    /**
     * 같은 secret으로 서명 검증하는 [JwtDecoder]. 리소스 서버와 테스트가 공유한다.
     * HS256 MAC 알고리즘으로 고정해 alg 혼동(예: none/RS256 다운그레이드)을 차단한다.
     * 기본 검증(서명·exp/nbf 타임스탬프)에 더해 issuer(iss)도 설정값과 일치해야 통과시킨다.
     */
    fun jwtDecoder(): JwtDecoder = decoder(admin = false)

    fun adminJwtDecoder(): JwtDecoder = decoder(admin = true)

    fun issueAdminToken(publicId: UUID): String {
        val now = clock.instant()
        val claims = JwtClaimsSet.builder().issuer(properties.issuer).subject(publicId.toString())
            .audience(listOf(adminProperties.audience)).issuedAt(now).expiresAt(now.plus(adminProperties.ttl)).build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MAC_ALGORITHM).build(), claims)).tokenValue
    }

    fun adminTtlSeconds(): Long = adminProperties.ttl.seconds

    private fun decoder(admin: Boolean): JwtDecoder {
        val decoder = NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MAC_ALGORITHM)
            .build()
        val claimConverter = org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter.withDefaults(emptyMap())
        decoder.setClaimSetConverter { claims ->
            // 기본 변환기가 aud:null을 지워도 앱 토큰으로 승격되지 않게 원본의 존재부터 검사한다.
            if (!admin && claims.containsKey("aud")) {
                throw org.springframework.security.oauth2.jwt.BadJwtException("유효하지 않은 인증입니다.")
            }
            claimConverter.convert(claims)
        }
        val timestamps = JwtTimestampValidator(Duration.ofSeconds(60)).apply { setClock(clock) }
        val tokenKind = OAuth2TokenValidator<Jwt> { jwt ->
            val valid = jwt.expiresAt != null && if (admin) {
                adminProperties.audience in jwt.audience.orEmpty() && runCatching { UUID.fromString(jwt.subject).toString().equals(jwt.subject, ignoreCase = true) }.getOrDefault(false)
            } else {
                !jwt.hasClaim("aud")
            }
            if (valid) OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(
                OAuth2Error("invalid_token", "유효하지 않은 인증입니다.", null),
            )
        }
        decoder.setJwtValidator(
            JwtValidators.createDefaultWithValidators(listOf(
                timestamps,
                tokenKind,
                JwtIssuerValidator(properties.issuer),
            )),
        )
        return decoder
    }

    private companion object {
        val MAC_ALGORITHM: MacAlgorithm = MacAlgorithm.HS256
    }
}
