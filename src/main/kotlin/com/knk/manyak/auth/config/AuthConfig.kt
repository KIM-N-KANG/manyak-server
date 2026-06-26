package com.knk.manyak.auth.config

import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.social.GoogleAuthProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * 인증 설정 바인딩과 JWT 검증 빈을 등록한다.
 *
 * - [AuthProperties](`manyak.auth.jwt`)와 [GoogleAuthProperties](`manyak.auth.google`)를 활성화(@EnableConfigurationProperties)한다.
 * - 리소스 서버가 Bearer 토큰을 검증할 때 쓰는 [JwtDecoder]를 [JwtTokenProvider](발급과 같은 secret)로 노출한다.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties::class, GoogleAuthProperties::class)
class AuthConfig {

    @Bean
    fun jwtDecoder(jwtTokenProvider: JwtTokenProvider): JwtDecoder = jwtTokenProvider.jwtDecoder()
}
