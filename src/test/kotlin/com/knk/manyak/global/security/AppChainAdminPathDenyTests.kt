package com.knk.manyak.global.security

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.FilterChainProxy

/**
 * 관리자 체인이 빠져도 앱 체인이 관리자 경로를 거부하는지 확인한다(KNK-2068 이중 방어).
 *
 * 컨텍스트의 실제 앱 체인(관리자 체인을 뺀 나머지 하나)만으로 [FilterChainProxy]를 만들어 요청을 태운다.
 * 새 Spring 컨텍스트를 만들지 않고, 관리자 체인이 등록되지 않은 상태를 그대로 재현한다.
 */
class AppChainAdminPathDenyTests : IntegrationTest() {
    @Autowired private lateinit var filterChainProxy: FilterChainProxy
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: JwtTokenProvider

    @BeforeEach fun clean() = cleaner.cleanAll()

    @Test
    fun `앱 체인만으로도 관리자 경로는 토큰 없음 401, 일반 회원 토큰 403으로 핸들러에 닿지 않는다`() {
        val member = tokens.issueAccessToken(users.save(User(nickname = "일반 회원")).publicId)
        for ((method, path) in listOf("GET" to "/api/v1/admin/members", "POST" to "/api/v1/admin/auth/login/google")) {
            val anonymous = throughAppChainOnly(method, path, token = null)
            assertThat(anonymous.reached).describedAs("토큰 없음 $method $path").isFalse()
            assertThat(anonymous.response.status).describedAs("토큰 없음 $method $path").isEqualTo(401)

            val authenticated = throughAppChainOnly(method, path, member)
            assertThat(authenticated.reached).describedAs("회원 토큰 $method $path").isFalse()
            assertThat(authenticated.response.status).describedAs("회원 토큰 $method $path").isEqualTo(403)
        }
    }

    @Test
    fun `같은 방식으로 앱 경로는 회원 토큰이면 핸들러까지 통과한다`() {
        // 위 거부가 이 테스트 장치 때문이 아니라 앱 체인 규칙 때문임을 보이는 대조군.
        val member = tokens.issueAccessToken(users.save(User(nickname = "일반 회원")).publicId)
        val result = throughAppChainOnly("GET", "/api/v1/users/me/credits", member)
        assertThat(result.reached).isTrue()
        assertThat(result.response.status).isEqualTo(200)
    }

    private class Result(val response: MockHttpServletResponse, val reached: Boolean)

    private fun throughAppChainOnly(method: String, path: String, token: String?): Result {
        // 관리자 체인은 관리자 경로만 잡으므로, 앱 경로를 잡는 체인은 앱 체인 하나뿐이다.
        val appRequest = MockHttpServletRequest("GET", "/api/v1/stories").apply { servletPath = "/api/v1/stories" }
        val appChain = filterChainProxy.filterChains.single { it.matches(appRequest) }
        val proxy = FilterChainProxy(appChain).apply { afterPropertiesSet() }
        val request = MockHttpServletRequest(method, path).apply {
            servletPath = path
            if (token != null) addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        var reached = false
        proxy.doFilter(request, response) { _, _ -> reached = true }
        return Result(response, reached)
    }
}
