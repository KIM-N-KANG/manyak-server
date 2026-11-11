package com.knk.manyak.global.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.context.request.ServletWebRequest
import java.util.UUID

/** 탈퇴 필터가 남긴 회원 PK를 리졸버가 재사용하는 규칙(KNK-2068). 인증 확인이 속성보다 먼저다. */
class CurrentUserIdArgumentResolverTest {
    private val publicId = UUID.randomUUID()
    private val lookups = mutableListOf<UUID>()
    private val resolver = CurrentUserIdArgumentResolver(object : AccountAccessPort {
        override fun userIdOf(publicId: UUID): Long? = 42L.also { lookups += publicId }
        override fun check(publicId: UUID, method: String, path: String) = error("리졸버는 판정하지 않는다")
    })

    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    private fun resolve(attribute: Long?): Long? {
        val request = MockHttpServletRequest("GET", "/api/v1/users/me/credits")
        attribute?.let { request.setAttribute(CurrentUserIdArgumentResolver.RESOLVED_USER_ID_ATTRIBUTE, it) }
        return resolver.resolveArgument(org.mockito.Mockito.mock(org.springframework.core.MethodParameter::class.java), null, ServletWebRequest(request), null)
    }

    private fun authenticate() {
        val jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(publicId.toString()).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `토큰 인증이고 필터가 남긴 회원 PK가 있으면 다시 조회하지 않고 그 값을 쓴다`() {
        authenticate()
        assertThat(resolve(attribute = 7L)).isEqualTo(7L)
        assertThat(lookups).isEmpty()
    }

    @Test
    fun `속성이 없으면 전과 같이 포트로 조회한다`() {
        authenticate()
        assertThat(resolve(attribute = null)).isEqualTo(42L)
        assertThat(lookups).containsExactly(publicId)
    }

    @Test
    fun `토큰 인증이 아니면 속성이 있어도 null이다`() {
        assertThat(resolve(attribute = 7L)).isNull()
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
        assertThat(resolve(attribute = 7L)).isNull()
        assertThat(lookups).isEmpty()
    }
}
