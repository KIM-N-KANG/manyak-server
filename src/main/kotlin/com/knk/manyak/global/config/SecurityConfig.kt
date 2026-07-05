package com.knk.manyak.global.config

import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.global.security.OptionalJwtAuthenticationFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain =
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
                    // 로어북 카탈로그는 인증 없이 조회하는 공개 목록이다(일반 제작 참조용). {storyId} 매처보다 앞에 둬 명시적으로 허용한다.
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/lorebooks")).permitAll()
                    // 스토리 ID도 추측 불가능한 공개 식별자(UUID)다(KNK-256). 형식을 제약하지 않고 모든 값을 통과시켜,
                    // 존재 여부 판단(404)은 서비스가 일관되게 처리한다. 순차 정수·임의 값 모두 404로 통일된다(IDOR 차단).
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/{storyId}")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/api/v1/stories/{storyId}")).permitAll()
                    // 주요 사건 조회·교체(KNK-418)는 다른 스토리 엔드포인트와 동일한 공개 식별자 기반 접근을 따른다.
                    // 저작 인증(오너 한정)은 스토리 편집 전반에 인증이 붙는 일반 제작·수정(KNK-45) 시 함께 다룬다.
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/{storyId}/main-events")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, "/api/v1/stories/{storyId}/main-events")).permitAll()
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
            // optional 인증 필터. 익명 허용(permitAll) 도메인 경로(OPTIONAL_AUTH_MATCHERS)에서만 동작하며,
            // 유효 access 토큰이면 principal(Jwt)을 채우고 토큰이 없거나 만료·위조면 익명으로 통과시킨다(401 없음).
            // 이 경로들은 아래 bearerTokenResolver에서 토큰 resolve를 건너뛰므로 RS 필터(BearerTokenAuthenticationFilter)가
            // 401을 내지 않고, 인증 시도는 이 필터만 수행한다. RS 필터보다 앞에 둬 동일 요청에서 principal을 먼저 확정한다.
            .addFilterBefore(
                OptionalJwtAuthenticationFilter(jwtDecoder, OrRequestMatcher(*OPTIONAL_AUTH_MATCHERS)),
                BearerTokenAuthenticationFilter::class.java,
            )
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
            if (
                BEARER_SKIP_MATCHERS.any { it.matches(request) } ||
                OPTIONAL_AUTH_MATCHERS.any { it.matches(request) }
            ) {
                // 공개 인증 경로(BEARER_SKIP) 또는 optional 인증 도메인 경로(OPTIONAL_AUTH)에서는
                // RS 필터가 토큰을 resolve하지 않게 해 만료/위조 헤더로 401이 나지 않게 한다.
                // OPTIONAL_AUTH 경로의 유효 토큰 귀속은 OptionalJwtAuthenticationFilter가 별도로 처리한다.
                null
            } else {
                delegate.resolve(request)
            }
        }
    }

    private companion object {
        // 공개 인증 경로. authorizeHttpRequests의 permitAll 매처와 동일한 경로·메서드로 맞춘다.
        // 여기에 든 경로는 permitAll이면서 동시에 Bearer 토큰 resolve를 건너뛴다(만료/위조 헤더 무시).
        val BEARER_SKIP_MATCHERS = arrayOf(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login/google"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/token/refresh"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/logout"),
        )

        // optional 인증 도메인 경로. authorizeHttpRequests의 permitAll 매처와 동일한 method·path로 맞춘다.
        // 이 경로들은 (1) RS 필터의 토큰 resolve를 건너뛰어 만료/위조 헤더로 401이 나지 않게 하고(아래 resolver),
        // (2) OptionalJwtAuthenticationFilter가 유효 access 토큰이면 principal(Jwt)을 채워 user_id 귀속을 가능하게 한다.
        val OPTIONAL_AUTH_MATCHERS = arrayOf(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/chats"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/chats/batch"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/chats/{chatId}"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/api/v1/chats/{chatId}"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/chats/{chatId}/turns/stream"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/lorebooks"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/{storyId}"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/api/v1/stories/{storyId}"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/{storyId}/main-events"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, "/api/v1/stories/{storyId}/main-events"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/simple/tags"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/simple/storylines"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/simple"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, "/api/v1/stories/simple/storylines/{storylineId:\\d+}/rating"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/api/v1/stories/simple/storylines/{storylineId:\\d+}/rating"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/batch"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/feedbacks"),
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
