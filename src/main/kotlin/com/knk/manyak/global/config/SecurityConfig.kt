package com.knk.manyak.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
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
                        "/api/v1/auth/**",
                        "/api/v1/chats/**",
                    ).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/stories/{storyId:\\d+}")).permitAll()
                    .anyRequest().authenticated()
            }
            .build()
}
