package com.knk.manyak.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * JWT 인증 설정. `manyak.auth.jwt` 프리픽스로 바인딩된다.
 *
 * - [secret]: access JWT를 HS256으로 서명·검증하는 대칭키. 운영에선 env(MANYAK_AUTH_JWT_SECRET)로 주입한다.
 *   HS256은 키 길이가 해시 출력(256bit) 이상이어야 하므로 **최소 32바이트(UTF-8)** 가 필요하다.
 *   이보다 짧으면 토큰 발급/검증 빈 초기화 시점에 실패하므로, 빈 값·짧은 값은 설정 오류로 본다.
 * - [issuer]: 발급자(iss) 클레임 값. 검증 시 동일 issuer만 허용한다.
 * - [accessTtl]: access 토큰 수명(짧게). 만료 시 refresh로 회전한다.
 * - [refreshTtl]: refresh 토큰 수명(길게). 저장소(RefreshTokenStore) TTL과 동일하다.
 */
@ConfigurationProperties(prefix = "manyak.auth.jwt")
data class AuthProperties(
    val secret: String,
    val issuer: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
)
