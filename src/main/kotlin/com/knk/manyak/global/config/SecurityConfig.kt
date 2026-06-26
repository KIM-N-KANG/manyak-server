package com.knk.manyak.global.config

import com.knk.manyak.global.observability.RequestCorrelationFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .cors { }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/error",
                    ).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/chats")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/chats/batch")).permitAll()
                    // 채팅 ID는 추측 불가능한 공개 식별자(UUID)다. 형식을 제약하지 않고 모든 값을 통과시켜,
                    // 존재 여부 판단(404)은 서비스가 일관되게 처리한다. 순차 정수·임의 값 모두 404로 통일된다.
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/chats/{chatId}")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/api/v1/chats/{chatId}")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/chats/{chatId}/turns/stream")).permitAll()
                    // 스토리 ID도 추측 불가능한 공개 식별자(UUID)다(KNK-256). 형식을 제약하지 않고 모든 값을 통과시켜,
                    // 존재 여부 판단(404)은 서비스가 일관되게 처리한다. 순차 정수·임의 값 모두 404로 통일된다(IDOR 차단).
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/{storyId}")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/api/v1/stories/{storyId}")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/simple/tags")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/simple/storylines")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/simple")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, "/api/v1/stories/simple/storylines/{storylineId:\\d+}/rating")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/api/v1/stories/simple/storylines/{storylineId:\\d+}/rating")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/batch")).permitAll()
                    // 피드백은 익명 제출을 허용한다. 로그인 상태면 인증 도입 후 서버가 user_id 를 채운다.
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/feedbacks")).permitAll()
                    // refresh 회전은 access 토큰 없이 호출하므로 인증을 요구하지 않는다(토큰 유효성은 서비스가 검증한다).
                    // /api/v1/auth/me 는 anyRequest().authenticated() 로 보호된다.
                    // 이 경로는 bearerTokenResolver에서도 토큰을 무시하므로, 자동 첨부된 만료/위조 access 헤더로 막히지 않는다.
                    .requestMatchers(REFRESH_PATH_MATCHER).permitAll()
                    .anyRequest().authenticated()
            }
            // Bearer access 토큰(HS256 JWT) 검증은 리소스 서버가 JwtDecoder 빈(AuthConfig)으로 수행한다.
            // 토큰 없음·만료·위조는 BearerTokenAuthenticationEntryPoint가 401로 응답한다.
            // refresh 경로에서는 토큰을 resolve하지 않아(아래 resolver) 자동 첨부된 만료/위조 access 헤더로 막히지 않는다.
            .oauth2ResourceServer {
                it.bearerTokenResolver(refreshAwareBearerTokenResolver())
                it.jwt { }
            }
            .build()

    /**
     * refresh 회전 경로(POST /api/v1/auth/token/refresh)에서는 Bearer 토큰을 resolve하지 않는다.
     *
     * 모바일 등 클라이언트가 인터셉터로 access 토큰을 모든 요청에 자동 첨부하면, 만료/위조된 access 헤더가
     * BearerTokenAuthenticationFilter에 걸려 인가(permitAll)보다 먼저 401이 난다. 그러면 유효한 refresh를
     * 들고도 컨트롤러에 도달하지 못한다. 이 경로에서만 토큰을 무시해(null) 인증 자체를 시도하지 않게 하고,
     * refresh 유효성은 AuthTokenService가 검증하게 한다. (단일 필터체인 유지 — 체인 분리 없이 cors/csrf/session 중복 회피)
     */
    private fun refreshAwareBearerTokenResolver(): BearerTokenResolver {
        val delegate = DefaultBearerTokenResolver()
        return BearerTokenResolver { request: HttpServletRequest ->
            if (REFRESH_PATH_MATCHER.matches(request)) null else delegate.resolve(request)
        }
    }

    private companion object {
        // authorizeHttpRequests의 permitAll 매처와 동일한 경로·메서드로 맞춘다.
        val REFRESH_PATH_MATCHER =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/token/refresh")
    }

    @Bean
    fun corsConfigurationSource(
        @Value("\${manyak.cors.allowed-origins}")
        allowedOrigins: String,
    ): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins.split(",").map { it.trim() }
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            // 응답에 echo한 request_id를 브라우저 JS가 읽을 수 있게 노출한다.
            // (커스텀 헤더는 CORS-safelisted가 아니라 exposedHeaders 없이는 cross-origin에서 읽히지 않는다.)
            exposedHeaders = listOf(RequestCorrelationFilter.HEADER_REQUEST_ID)
        }

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}
