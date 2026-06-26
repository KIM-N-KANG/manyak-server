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
import java.time.Duration
import java.util.Optional

/**
 * AuthTokenService의 발급/회전/로그아웃 계약을 고정한다.
 *
 * refresh 저장은 [InMemoryRefreshTokenStore], JWT는 실제 [JwtTokenProvider]로 검증한다.
 * 사용자 조회(UserRepository.findById)만 mock으로 대체한다(JPA는 이 단위 테스트의 관심사 밖).
 *
 * - issueTokens: access + 새 refresh를 발급하고 새 세션 family를 만든다.
 * - rotate: 현재 토큰 → old 폐기 + new 발급. 무효/사용자 부재/재사용 → 401(재사용 시 family 폐기).
 * - logout: family 단위 폐기(동시 회전으로 발급된 새 토큰까지 무효화).
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

    /** id가 고정된 User를 만들고, repository.findById(id)가 이를 반환하도록 스텁한다. */
    private fun registerUser(id: Long): User {
        val user = User(id = id, nickname = "닉네임")
        `when`(userRepository.findById(id)).thenReturn(Optional.of(user))
        return user
    }

    @Test
    fun `issueTokens는 access와 refresh를 발급하고 발급된 refresh로 회전할 수 있다`() {
        val user = registerUser(1L)

        val response = service.issueTokens(user)

        assertThat(response.accessToken).isNotBlank()
        assertThat(response.refreshToken).isNotBlank()
        assertThat(response.tokenType).isEqualTo("Bearer")
        assertThat(response.expiresIn).isEqualTo(properties.accessTtl.seconds)
        // 발급된 refresh가 family에 저장돼 회전된다(저장은 원문이 아니라 내부 해시로 이뤄진다).
        assertThat(service.rotate(response.refreshToken).accessToken).isNotBlank()
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

        // 새 refresh가 발급되고(다른 값), 그 토큰으로 또 회전된다.
        assertThat(rotated.refreshToken).isNotEqualTo(issued.refreshToken)
        assertThat(service.rotate(rotated.refreshToken).accessToken).isNotBlank()
    }

    @Test
    fun `회전 후 옛 refresh를 재사용하면 401이고 family 전체가 폐기된다`() {
        val user = registerUser(4L)
        val issued = service.issueTokens(user)
        val rotated = service.rotate(issued.refreshToken)

        // 옛(이미 회전된) refresh 재사용 → 401.
        assertThatThrownBy { service.rotate(issued.refreshToken) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
        // family 폐기 → 직전까지 유효하던 현재 토큰도 무효(401).
        assertThatThrownBy { service.rotate(rotated.refreshToken) }
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
    fun `회전은 성공했지만 사용자가 사라진 경우 401이다`() {
        // 매핑된 사용자가 삭제된 경우(토큰 누수 후 계정 삭제 등).
        val user = registerUser(5L)
        val issued = service.issueTokens(user)
        `when`(userRepository.findById(anyLong())).thenReturn(Optional.empty())

        assertThatThrownBy { service.rotate(issued.refreshToken) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `logout은 family를 폐기해 이후 rotate가 401이다`() {
        val user = registerUser(6L)
        val issued = service.issueTokens(user)

        service.logout(issued.refreshToken)

        assertThatThrownBy { service.rotate(issued.refreshToken) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `logout은 과거 토큰으로도 family 전체를 폐기한다 (동시 회전으로 발급된 새 토큰까지 무효)`() {
        val user = registerUser(7L)
        val issued = service.issueTokens(user)
        val rotated = service.rotate(issued.refreshToken) // 동시 회전이 새 토큰을 발급한 상황 모사.

        // 로그아웃이 과거 토큰(issued)을 제시해도 family가 통째로 폐기된다.
        service.logout(issued.refreshToken)

        assertThatThrownBy { service.rotate(rotated.refreshToken) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `발급된 적 없는 refresh를 logout해도 예외 없이 멱등하다`() {
        service.logout("never-issued-token")
    }
}
