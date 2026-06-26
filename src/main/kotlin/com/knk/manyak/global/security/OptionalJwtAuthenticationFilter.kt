package com.knk.manyak.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

/**
 * optional 인증 필터. 지정한 [requestMatcher] 경로에서 Bearer access 토큰이 유효하면 principal(Jwt)을 채우고,
 * 토큰이 없거나 만료·위조됐으면 인증을 채우지 않고 익명으로 통과시킨다(절대 401을 내지 않는다).
 *
 * 익명 허용(permitAll) 도메인 경로에 로그인 유저의 user_id 귀속을 가능하게 한다. 이 경로들은 SecurityConfig의
 * bearerTokenResolver에서 토큰 resolve를 건너뛰므로(RS 필터의 401 차단), 인증 시도는 오직 이 필터만 수행한다.
 * 따라서 무효/만료 토큰은 [JwtException]을 삼켜 익명으로 둔다.
 */
class OptionalJwtAuthenticationFilter(
    private val jwtDecoder: JwtDecoder,
    private val requestMatcher: RequestMatcher,
) : OncePerRequestFilter() {
    private val converter = JwtAuthenticationConverter()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (requestMatcher.matches(request) && SecurityContextHolder.getContext().authentication == null) {
            resolveBearer(request)?.let { token ->
                try {
                    val jwt = jwtDecoder.decode(token)
                    SecurityContextHolder.getContext().authentication = converter.convert(jwt)
                } catch (ex: JwtException) {
                    // 무효/만료 토큰 → 무시하고 익명 통과(401 없음).
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveBearer(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith("Bearer ", ignoreCase = true)) {
            header.substring(7).trim().ifEmpty { null }
        } else {
            null
        }
    }
}
