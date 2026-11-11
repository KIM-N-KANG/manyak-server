package com.knk.manyak.global.security

import com.knk.manyak.global.util.parsePublicIdOrNull
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.ApiErrorResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * 탈퇴(DELETED) 계정의 잔여 access 토큰을 인증 필터 계층에서 전면 거부한다(스펙 §4-3-5, KNK-1019).
 *
 * refresh는 탈퇴 시 폐기되지만 access는 만료까지 최대 30분 살아 있다. 엔드포인트가 `@CurrentUserId`를
 * 쓰는지와 무관하게(공유 조회처럼 principal을 직접 안 읽는 공개 경로 포함) 같은 계약을 보장하려면
 * 인증이 확정된 직후의 공통 지점이 여기뿐이다. JWT 인증이 없는 요청(익명·공개)은 조회 없이 통과한다.
 *
 * 탈퇴와 동의 게이트의 판정은 계정 모듈이 [AccountAccessPort]로 구현하고(KNK-2068), 이 필터는 판정에 맞는 응답만 쓴다.
 */
class DeletedAccountRejectionFilter(
    private val accountAccess: AccountAccessPort,
    // 필터 계층이라 GlobalExceptionHandler를 못 타므로 같은 오류 바디(ApiErrorResponse)를 직접 쓴다.
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken) {
            val access = parsePublicIdOrNull(authentication.token.subject)
                ?.let { accountAccess.check(it, request.method, request.servletPath) }
            if (access == AccountAccess.DELETED) {
                SecurityContextHolder.clearContext()
                response.status = HttpStatus.UNAUTHORIZED.value()
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.characterEncoding = Charsets.UTF_8.name()
                response.writer.write(
                    objectMapper.writeValueAsString(
                        ApiErrorResponse(
                            status = HttpStatus.UNAUTHORIZED.value(),
                            code = HttpStatus.UNAUTHORIZED.name,
                            message = "유효하지 않은 인증입니다.",
                            path = request.requestURI,
                        ),
                    ),
                )
                return
            }
            if (access == AccountAccess.CONSENT_REQUIRED) {
                response.status = HttpStatus.FORBIDDEN.value()
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.characterEncoding = Charsets.UTF_8.name()
                response.writer.write(objectMapper.writeValueAsString(ApiErrorResponse(
                    status = HttpStatus.FORBIDDEN.value(), code = ApiErrorCodes.CONSENT_REQUIRED,
                    message = "현행 필수 항목에 동의해 주세요.", path = request.requestURI,
                )))
                return
            }
        }
        filterChain.doFilter(request, response)
    }
}
