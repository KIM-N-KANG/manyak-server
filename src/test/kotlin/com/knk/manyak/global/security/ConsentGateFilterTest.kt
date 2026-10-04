package com.knk.manyak.global.security

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.user.consent.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import tools.jackson.databind.ObjectMapper

class ConsentGateFilterTest {
    private val users = mock(UserRepository::class.java)
    private val consents = mock(UserConsentService::class.java)
    private val user = User(id = 7, nickname = "회원")
    private val missing = UserConsentResponse(ConsentStatusResponse("v1.4", true), ConsentStatusResponse("v1.7", false), ConsentStatusResponse("1", false))

    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    private fun request(method: String, path: String, enabled: Boolean = true, principal: Boolean = true): Pair<MockHttpServletResponse, Boolean> {
        if (principal) {
            val jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject(user.publicId.toString()).build()
            SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        }
        `when`(users.findByPublicId(user.publicId)).thenReturn(user)
        val request = MockHttpServletRequest(method, path).apply { servletPath = path }
        val response = MockHttpServletResponse()
        var passed = false
        DeletedAccountRejectionFilter(users, ObjectMapper(), consents, enabled).doFilter(request, response) { _, _ -> passed = true }
        return response to passed
    }

    @Test fun `게이트 off와 무인증은 동의 저장소를 조회하지 않는다`() {
        assertThat(request("GET", "/api/v1/stories", enabled = false).second).isTrue()
        SecurityContextHolder.clearContext()
        assertThat(request("GET", "/api/v1/stories", principal = false).second).isTrue()
        verifyNoInteractions(consents)
    }

    @Test fun `메서드와 경로가 정확한 허용 목록만 통과한다`() {
        val allowed = listOf("GET" to "/users/me/consents", "POST" to "/users/me/consents", "GET" to "/auth/me",
            "DELETE" to "/users/me", "DELETE" to "/users/me/push-tokens", "GET" to "/credits/policies",
            "GET" to "/credits/products", "GET" to "/profile-presets", "GET" to "/stories/simple/tags", "GET" to "/stories/genres")
        allowed.forEach { (method, path) -> assertThat(request(method, "/api/v1$path").second).describedAs("$method $path").isTrue() }
        verifyNoInteractions(consents)
        `when`(consents.authenticationStatus(user.id)).thenReturn(missing)
        for ((method, path) in listOf("POST" to "/stories/genres", "GET" to "/stories/genres/private", "PUT" to "/users/me/push-tokens", "GET" to "/stories", "GET" to "/users/me/trials")) {
            val (response, passed) = request(method, "/api/v1$path")
            assertThat(passed).isFalse()
            assertThat(response.status).isEqualTo(403)
            assertThat(response.contentAsString).contains("CONSENT_REQUIRED")
        }
    }

    @Test fun `현행 동의가 모두 있으면 일반 요청도 통과한다`() {
        `when`(consents.authenticationStatus(user.id)).thenReturn(missing.copy(terms = ConsentStatusResponse("v1.4", false)))
        assertThat(request("POST", "/api/v1/chats").second).isTrue()
    }

    @Test fun `DELETED는 허용 경로와 게이트 설정에 관계없이 먼저 401이다`() {
        user.status = UserStatus.DELETED
        for (enabled in listOf(true, false)) {
            val (response, passed) = request("GET", "/api/v1/auth/me", enabled)
            assertThat(passed).isFalse()
            assertThat(response.status).isEqualTo(401)
        }
        verifyNoInteractions(consents)
    }
}
