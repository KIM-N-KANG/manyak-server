package com.knk.manyak.admin.security

import com.knk.manyak.admin.service.AdminEligibilityService
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.service.AdminCandidateQuery
import com.knk.manyak.global.error.GlobalExceptionHandler
import com.knk.manyak.global.security.ApiAuthenticationEntryPoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import tools.jackson.databind.ObjectMapper

/**
 * 관리자 경로(`/api/v1/admin/` 아래 전체) 전용 보안 필터 체인(ADMIN 5-1-1). 앱 체인([com.knk.manyak.global.config.SecurityConfig])은
 * 매처 없이 순서 미지정(최저 우선)이므로, 이 체인이 `@Order(1)`로 먼저 관리자 경로를 가져간다.
 *
 * [AdminAuthenticationFilter]는 빈으로 노출하지 않고 여기서 생성한다. 빈이 되면 Spring Boot가 서블릿 필터로도 등록해
 * 보안 체인 밖에서 모든 경로에 한 번 더 돈다. CORS는 global의 `corsConfigurationSource` 빈을 그대로 쓴다.
 */
@Configuration
class AdminSecurityConfig {

    @Bean
    @Order(1)
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        tokens: JwtTokenProvider,
        candidates: AdminCandidateQuery,
        eligibility: AdminEligibilityService,
        mapper: ObjectMapper,
        errors: GlobalExceptionHandler,
    ): SecurityFilterChain = http
        .securityMatcher("/api/v1/admin/**")
        .cors { }
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,
                AdminAuthenticationFilter.LOGIN_PATH)).permitAll()
                .anyRequest().authenticated()
        }
        .exceptionHandling { it.authenticationEntryPoint(ApiAuthenticationEntryPoint(mapper)) }
        .addFilterBefore(AdminAuthenticationFilter(
            tokens.adminJwtDecoder(), candidates, eligibility, mapper, errors,
        ), BearerTokenAuthenticationFilter::class.java)
        .build()
}
