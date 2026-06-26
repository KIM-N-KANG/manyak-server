package com.knk.manyak.auth.token

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Optional

/**
 * AuthTokenService의 발급/회전 계약을 고정한다.
 *
 * refresh 저장은 [InMemoryRefreshTokenStore], JWT는 실제 [JwtTokenProvider]로 검증한다.
 * 사용자 조회(UserRepository.findById)만 mock으로 대체한다(이 서비스 단위 테스트에서 JPA는 관심사 밖).
 *
 * - issueTokens: access + 새 refresh를 발급하고, refresh **해시**(원문 아님)를 store에 userId로 저장한다.
 * - rotate: 유효한 refresh → old 폐기 + new 발급(회전). 무효/재사용 refresh → 401.
 */
class AuthTokenServiceTest {

    private val store = InMemoryRefreshTokenStore()
    private val properties = AuthProperties(
        secret = "test-only-jwt-secret-fixed-value-32-bytes-min",
        issuer = "manyak",
        accessTtl = Duration.ofMinutes(30),
        refreshTtl = Duration.ofDays(14),
    )
    private val jwtTokenProvider = JwtTokenProvider(properties)
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val service = AuthTokenService(jwtTokenProvider, store, userRepository, properties)

    private fun sha256Base64Url(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** id가 고정된 User를 만들고, repository.findById(id)가 이를 반환하도록 스텁한다. */
    private fun registerUser(id: Long): User {
        val user = User(id = id, nickname = "닉네임")
        `when`(userRepository.findById(id)).thenReturn(Optional.of(user))
        return user
    }

    @Test
    fun `issueTokens는 access와 refresh를 발급하고 refresh 해시를 store에 저장한다`() {
        val user = registerUser(1L)

        val response = service.issueTokens(user)

        assertThat(response.accessToken).isNotBlank()
        assertThat(response.refreshToken).isNotBlank()
        assertThat(response.tokenType).isEqualTo("Bearer")
        assertThat(response.expiresIn).isEqualTo(properties.accessTtl.seconds)

        // 원문이 아니라 해시가 저장돼야 한다. (원문 키로는 조회되지 않고, 해시 키로는 userId가 나온다.)
        assertThat(store.findUserId(response.refreshToken)).isNull()
        assertThat(store.findUserId(sha256Base64Url(response.refreshToken))).isEqualTo(user.id)
    }

    @Test
    fun `발급한 access 토큰은 sub에 user publicId를 담는다`() {
        val user = registerUser(2L)

        val response = service.issueTokens(user)
        val jwt = jwtTokenProvider.jwtDecoder().decode(response.accessToken)

        assertThat(jwt.subject).isEqualTo(user.publicId.toString())
    }

    @Test
    fun `rotate는 old refresh를 폐기하고 새 토큰을 발급한다`() {
        val user = registerUser(3L)
        val issued = service.issueTokens(user)

        val rotated = service.rotate(issued.refreshToken)

        // 새 refresh가 발급되고(다른 값), 새 해시가 저장된다.
        assertThat(rotated.refreshToken).isNotEqualTo(issued.refreshToken)
        assertThat(store.findUserId(sha256Base64Url(rotated.refreshToken))).isEqualTo(user.id)
        // old refresh 해시는 더 이상 조회되지 않는다(폐기).
        assertThat(store.findUserId(sha256Base64Url(issued.refreshToken))).isNull()
    }

    @Test
    fun `회전 후 옛 refresh를 재사용하면 401이다`() {
        val user = registerUser(4L)
        val issued = service.issueTokens(user)
        service.rotate(issued.refreshToken)

        assertThatThrownBy { service.rotate(issued.refreshToken) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `무효한 refresh로 rotate하면 401이다`() {
        assertThatThrownBy { service.rotate("never-issued-token") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `store에는 있지만 사용자가 사라진 refresh로 rotate하면 401이다`() {
        // 저장된 매핑이 가리키는 사용자가 삭제된(없는) 경우. 토큰 누수 후 계정 삭제 등.
        val user = registerUser(5L)
        val issued = service.issueTokens(user)
        `when`(userRepository.findById(anyLong())).thenReturn(Optional.empty())

        assertThatThrownBy { service.rotate(issued.refreshToken) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `logout은 refresh를 폐기해 이후 rotate가 401이다`() {
        val user = registerUser(6L)
        val issued = service.issueTokens(user)

        service.logout(issued.refreshToken)

        // 폐기됐으므로 store에서 더 이상 조회되지 않는다.
        assertThat(store.findUserId(sha256Base64Url(issued.refreshToken))).isNull()
        // 폐기된 refresh로는 회전할 수 없다(401).
        assertThatThrownBy { service.rotate(issued.refreshToken) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `발급된 적 없는 refresh를 logout해도 예외 없이 멱등하다`() {
        // 멱등: 없는 토큰 폐기는 무시한다(예외를 던지지 않는다).
        service.logout("never-issued-token")
    }
}
