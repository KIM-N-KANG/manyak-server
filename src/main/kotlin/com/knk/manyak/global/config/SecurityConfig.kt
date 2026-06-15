package com.knk.manyak.global.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
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
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/chats/{chatId:\\d+}")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/chats/{chatId:\\d+}/turns/stream")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/{storyId:\\d+}")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/simple/tags")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/simple/storylines")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/simple")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/stories/batch")).permitAll()
                    .anyRequest().authenticated()
            }
            .build()

    @Bean
    fun corsConfigurationSource(
        @Value("\${manyak.cors.allowed-origins}")
        allowedOrigins: String,
    ): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins.split(",").map { it.trim() }
            allowedMethods = listOf("GET", "POST", "OPTIONS")
            allowedHeaders = listOf("*")
        }

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}
