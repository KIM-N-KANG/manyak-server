package com.knk.manyak.auth.token

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
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
 *   저장소에는 **원문이 아니라 SHA-256 해시**만 (해시→userId)로 둔다. 저장소 유출 시 원문 노출을 막는다.
 *
 * 회전(rotate): 유효한 refresh를 받으면 그 해시를 폐기하고 새 access+refresh를 발급한다.
 * 무효·만료·재사용(이미 회전된) refresh, 또는 매핑된 사용자가 없으면 401로 거부한다.
 */
@Service
class AuthTokenService(
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenStore: RefreshTokenStore,
    private val userRepository: UserRepository,
    private val properties: AuthProperties,
) {

    private val secureRandom = SecureRandom()

    /** access + 새 refresh를 발급한다. refresh 해시를 store에 userId로 저장한다. (로그인·회전에서 재사용) */
    fun issueTokens(user: User): TokenResponse {
        val accessToken = jwtTokenProvider.issueAccessToken(user.publicId)
        val rawRefresh = generateRefreshToken()
        refreshTokenStore.save(
            tokenHash = hash(rawRefresh),
            userId = user.id,
            ttl = properties.refreshTtl,
        )
        return TokenResponse(
            accessToken = accessToken,
            refreshToken = rawRefresh,
            expiresIn = jwtTokenProvider.accessTtlSeconds(),
        )
    }

    /**
     * refresh를 회전한다. old를 폐기하고 새 access+refresh를 발급한다.
     * 무효/재사용/사용자 부재는 모두 401(UNAUTHORIZED)로 통일한다(존재 여부를 노출하지 않는다).
     */
    fun rotate(rawRefresh: String): TokenResponse {
        val oldHash = hash(rawRefresh)
        // 회전은 원자적 소비(consume=GETDEL)로 한다. 동시 요청이 같은 refresh로 둘 다 발급하는 레이스를 막는다.
        // (findUserId 후 delete를 따로 부르면 두 호출 사이에 다른 요청이 끼어들 수 있다.)
        val userId = refreshTokenStore.consume(oldHash) ?: throw unauthorized()
        val user = userRepository.findById(userId).orElseThrow { unauthorized() }
        return issueTokens(user)
    }

    /**
     * 제시된 refresh를 폐기해 재발급(회전)을 막는다(단일 기기 로그아웃).
     * 원문을 해시해 store에서 **원자적으로 제거**한다(consume=GETDEL). 회전(rotate)도 같은 원자 소비를 쓰므로,
     * 동시 logout·refresh가 단일 Redis 명령에서 경합해 한쪽만 토큰을 소비한다(폐기와 회전이 둘 다 일어나는 레이스 차단).
     * 멱등하다 — 없는 토큰은 무시한다. (전체 기기 로그아웃은 범위 밖. 후속에서 deleteAllForUser로 다룬다.)
     */
    fun logout(rawRefresh: String) {
        refreshTokenStore.consume(hash(rawRefresh))
    }

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
