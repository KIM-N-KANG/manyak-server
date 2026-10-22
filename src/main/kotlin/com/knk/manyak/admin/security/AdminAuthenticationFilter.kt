package com.knk.manyak.admin.security

import com.knk.manyak.admin.service.AdminEligibilityService
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.error.ApiErrorResponse
import com.knk.manyak.global.security.ApiAuthenticationEntryPoint
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.core.AuthenticationException
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.util.UUID

class AdminAuthenticationFilter(
    private val decoder: JwtDecoder,
    private val users: UserRepository,
    private val eligibility: AdminEligibilityService,
    private val mapper: ObjectMapper,
    private val errors: com.knk.manyak.global.error.GlobalExceptionHandler,
) : OncePerRequestFilter() {
    private val resolver = DefaultBearerTokenResolver()
    private val entryPoint = ApiAuthenticationEntryPoint(mapper)
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "POST" && request.servletPath == LOGIN_PATH

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val jwt = try {
            val token = resolver.resolve(request) ?: throw BadCredentialsException("인증이 필요합니다.")
            decoder.decode(token)
        } catch (_: JwtException) {
            entryPoint.commence(request, response, BadCredentialsException("유효하지 않은 인증입니다."))
            return
        } catch (_: AuthenticationException) {
            entryPoint.commence(request, response, BadCredentialsException("유효하지 않은 인증입니다."))
            return
        }
        val user = try {
            users.findByPublicId(UUID.fromString(jwt.subject))
        } catch (ex: org.springframework.dao.DataAccessException) {
            storageFailure(response, request)
            return
        }
        val rejection = try { eligibility.rejectionReason(user) } catch (ex: org.springframework.dao.DataAccessException) {
            storageFailure(response, request)
            return
        }
        if (rejection != null) {
            log.warn("admin_access_denied endpoint={}, reason={}, admin_public_id={}", request.requestURI, rejection, jwt.subject)
            fail(response, request, HttpStatus.FORBIDDEN, "관리자 권한이 없습니다.")
            return
        }
        request.setAttribute(AdminRequester.ATTRIBUTE, requireNotNull(user).id)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationConverter().convert(jwt)
        chain.doFilter(request, response)
    }

    private fun storageFailure(response: HttpServletResponse, request: HttpServletRequest) {
        // DB 예외 원문에는 식별자·쿼리가 있을 수 있어 안전한 예외로 관측한다.
        val safe = org.springframework.web.server.ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.")
        errors.handleResponseStatusException(safe, request)
        fail(response, request, HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.")
    }

    private fun fail(response: HttpServletResponse, request: HttpServletRequest, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(mapper.writeValueAsString(ApiErrorResponse(status = status.value(), code = status.name, message = message, path = request.requestURI)))
    }

    companion object { const val LOGIN_PATH = "/api/v1/admin/auth/login/google" }
}
