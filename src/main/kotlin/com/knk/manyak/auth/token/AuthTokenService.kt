package com.knk.manyak.auth.token

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * access(JWT) + refresh(불투명 토큰) 발급과 refresh 회전을 담당한다.
 *
 * 토큰 모델:
 * - access: HS256 JWT(짧은 TTL). 클라이언트가 Authorization 헤더로 보내고 리소스 서버가 검증한다.
 * - refresh: 256bit 랜덤(SecureRandom) base64url 문자열(JWT 아님, 긴 TTL).
 *   저장소에는 **원문이 아니라 SHA-256 해시**만 둔다(저장소 유출 시 원문 노출 차단).
 *
 * 세션은 로그인 시 생성되는 refresh "family" 단위로 관리한다([RefreshTokenStore]).
 * - 회전(rotate): 같은 family의 현재 토큰을 받으면 폐기하고 새 access+refresh를 발급한다.
 * - 재사용(이미 회전된 과거 토큰) 탐지 시 family 전체를 폐기한다(탈취 대응).
 * - 로그아웃: family 전체를 폐기해 동시 회전 경합에도 세션이 살아남지 못하게 한다.
 */
@Service
class AuthTokenService(
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenStore: RefreshTokenStore,
    private val userRepository: UserRepository,
    private val properties: AuthProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    /** 로그인: access + 새 refresh를 발급하고 새 세션 family를 만든다. (Google 로그인 등에서 호출) */
    fun issueTokens(user: User): TokenResponse {
        val accessToken = jwtTokenProvider.issueAccessToken(user.publicId)
        val rawRefresh = generateRefreshToken()
        refreshTokenStore.createFamily(
            tokenHash = hash(rawRefresh),
            userId = user.id,
            ttl = properties.refreshTtl,
        )
        return tokenResponse(accessToken, rawRefresh)
    }

    /**
     * refresh를 회전한다. 같은 family의 현재 토큰이면 old를 폐기하고 새 access+refresh를 발급한다.
     * 무효/만료/사용자 부재는 401. 이미 회전된 과거 토큰의 재사용이면 family 전체를 폐기하고 401(탈취 정황 로깅).
     */
    fun rotate(rawRefresh: String): TokenResponse {
        val presentedHash = hash(rawRefresh)
        val newRaw = generateRefreshToken()
        val newHash = hash(newRaw)
        return when (val result = refreshTokenStore.rotate(presentedHash, newHash, properties.refreshTtl)) {
            is RotateResult.Rotated -> {
                val user = userRepository.findById(result.userId).orElse(null)
                if (user == null) {
                    // 회전은 성공했으나 매핑된 사용자가 사라진 경우(계정 삭제 등): 방금 발급된 토큰을 포함해 family를 폐기한다.
                    refreshTokenStore.revokeFamilyByToken(newHash)
                    throw unauthorized()
                }
                tokenResponse(jwtTokenProvider.issueAccessToken(user.publicId), newRaw)
            }
            is RotateResult.ReuseDetected -> {
                // 이미 회전된(폐기된) 토큰의 재사용 → 탈취 가능성. family는 store가 이미 폐기했다.
                log.warn("refresh 토큰 재사용 탐지: userId={} — 해당 세션 family를 폐기했다.", result.userId)
                throw unauthorized()
            }
            RotateResult.Invalid -> throw unauthorized()
        }
    }

    /**
     * 제시된 refresh가 속한 세션 family 전체를 폐기한다(로그아웃).
     * family 단위 폐기라, 동시 회전으로 새 토큰이 발급됐더라도 그 family가 사라져 세션이 살아남지 못한다.
     * 멱등하다 — 없는 토큰은 무시한다.
     */
    fun logout(rawRefresh: String) {
        refreshTokenStore.revokeFamilyByToken(hash(rawRefresh))
    }

    private fun tokenResponse(accessToken: String, rawRefresh: String): TokenResponse =
        TokenResponse(
            accessToken = accessToken,
            refreshToken = rawRefresh,
            expiresIn = jwtTokenProvider.accessTtlSeconds(),
        )

    /** 256bit 랜덤을 base64url(패딩 없음)로 인코딩한 불투명 refresh 토큰을 만든다. */
    private fun generateRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** refresh 원문의 SHA-256 해시를 base64url(패딩 없음)로 반환한다. 저장 키로만 쓴다. */
    private fun hash(rawRefresh: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawRefresh.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun unauthorized(): ResponseStatusException =
        ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh 토큰입니다.")

    private companion object {
        const val REFRESH_TOKEN_BYTES = 32 // 256bit
    }
}
