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
                    // 인증 없이 호출하는 공개 인증 엔드포인트(Google 로그인, refresh 회전).
                    // - 로그인: 아직 우리 토큰이 없는 상태에서 호출한다.
                    // - refresh: access 없이 회전한다(토큰 유효성은 서비스가 검증한다).
                    // 두 경로 모두 bearerTokenResolver에서도 토큰을 무시하므로(아래 resolver),
                    // 클라이언트가 자동 첨부한 만료/위조 access 헤더로 막히지 않는다.
                    // /api/v1/auth/me 는 anyRequest().authenticated() 로 보호된다.
                    .requestMatchers(*BEARER_SKIP_MATCHERS).permitAll()
                    .anyRequest().authenticated()
            }
            // Bearer access 토큰(HS256 JWT) 검증은 리소스 서버가 JwtDecoder 빈(AuthConfig)으로 수행한다.
            // 토큰 없음·만료·위조는 BearerTokenAuthenticationEntryPoint가 401로 응답한다.
            // 공개 인증 경로(BEARER_SKIP_MATCHERS)에서는 토큰을 resolve하지 않아(아래 resolver) 자동 첨부된 만료/위조 access 헤더로 막히지 않는다.
            .oauth2ResourceServer {
                it.bearerTokenResolver(bearerSkipAwareResolver())
                it.jwt { }
            }
            .build()

    /**
     * 공개 인증 경로(Google 로그인, refresh 회전)에서는 Bearer 토큰을 resolve하지 않는다.
     *
     * 모바일 등 클라이언트가 인터셉터로 access 토큰을 모든 요청에 자동 첨부하면, 만료/위조된 access 헤더가
     * BearerTokenAuthenticationFilter에 걸려 인가(permitAll)보다 먼저 401이 난다. 그러면 로그인/회전을
     * 시도하지도 못한다(로그아웃 후 stale access를 들고 다시 로그인하는 경우 등). 이 경로들에서만 토큰을
     * 무시해(null) 인증 자체를 시도하지 않게 하고, 검증은 각 서비스(Google verifier·AuthTokenService)가 한다.
     * (단일 필터체인 유지 — 체인 분리 없이 cors/csrf/session 중복 회피)
     */
    private fun bearerSkipAwareResolver(): BearerTokenResolver {
        val delegate = DefaultBearerTokenResolver()
        return BearerTokenResolver { request: HttpServletRequest ->
            if (BEARER_SKIP_MATCHERS.any { it.matches(request) }) null else delegate.resolve(request)
        }
    }

    private companion object {
        // 공개 인증 경로. authorizeHttpRequests의 permitAll 매처와 동일한 경로·메서드로 맞춘다.
        // 여기에 든 경로는 permitAll이면서 동시에 Bearer 토큰 resolve를 건너뛴다(만료/위조 헤더 무시).
        val BEARER_SKIP_MATCHERS = arrayOf(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login/google"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/token/refresh"),
        )
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
