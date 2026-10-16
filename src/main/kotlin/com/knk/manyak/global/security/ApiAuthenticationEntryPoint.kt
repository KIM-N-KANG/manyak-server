package com.knk.manyak.global.security

import com.knk.manyak.global.error.ApiErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import tools.jackson.databind.ObjectMapper

/** 필터 계층의 인증 실패도 API 공통 형식으로 응답하며 검증 예외의 원문을 노출하지 않는다. */
class ApiAuthenticationEntryPoint(private val objectMapper: ObjectMapper) : AuthenticationEntryPoint {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        // Bearer 해석기·JWT 검증기 및 확장 decoder가 넘기는 메시지는 토큰·클레임을 포함할 수 있다.
        // 원인 체인과 메시지는 읽지 않고 클래스만 기록한다(ACCOUNT 5-1-6, 5-4-8).
        val cause = authException.cause
        if (cause != null) {
            log.warn("Authentication failed: status=401, path={}, cause={}", request.requestURI, cause.javaClass.simpleName)
        } else {
            log.debug("Authentication failed: status=401, path={}, exception={}", request.requestURI, authException.javaClass.simpleName)
        }
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.setHeader("WWW-Authenticate", "Bearer")
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(objectMapper.writeValueAsString(ApiErrorResponse(
            status = HttpStatus.UNAUTHORIZED.value(),
            code = HttpStatus.UNAUTHORIZED.name,
            message = "유효하지 않은 인증입니다.",
            path = request.requestURI,
        )))
    }
}
