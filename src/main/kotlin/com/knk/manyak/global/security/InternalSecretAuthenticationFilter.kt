package com.knk.manyak.global.security

import com.knk.manyak.global.error.ApiErrorResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

class InternalSecretAuthenticationFilter(
    sharedSecret: String,
    private val requestMatcher: RequestMatcher,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val enabled = sharedSecret.isNotBlank()
    private val secretBytes = sharedSecret.toByteArray(Charsets.UTF_8)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !requestMatcher.matches(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val status = if (!enabled) {
            HttpStatus.NOT_FOUND
        } else {
            val supplied = request.getHeader("X-Manyak-Internal-Secret")
            if (supplied == null || !MessageDigest.isEqual(secretBytes, supplied.toByteArray(Charsets.UTF_8))) {
                HttpStatus.UNAUTHORIZED
            } else {
                null
            }
        }
        if (status != null) {
            // 필터 예외는 컨트롤러의 오류 핸들러에 도달하지 않으므로 같은 오류 형식으로 응답한다.
            response.status = status.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write(objectMapper.writeValueAsString(ApiErrorResponse(
                status = status.value(),
                code = status.name,
                message = if (status == HttpStatus.NOT_FOUND) "요청한 리소스를 찾을 수 없습니다." else "유효하지 않은 인증입니다.",
                path = request.requestURI,
            )))
            return
        }
        filterChain.doFilter(request, response)
    }
}
