package com.knk.manyak.auth.social

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Google 로그인 설정. `manyak.auth.google` 프리픽스로 바인딩된다.
 *
 * - [clientIds]: 허용할 OAuth client-id 목록. Google ID 토큰의 `aud`가 이 중 하나와 일치해야 통과한다.
 *   콤마 구분 환경변수(MANYAK_GOOGLE_CLIENT_IDS)로 주입하며, 운영 값은 하드코딩하지 않는다.
 *   여러 플랫폼(웹/안드로이드/iOS)별 client-id를 함께 허용하기 위해 목록으로 둔다.
 */
@ConfigurationProperties(prefix = "manyak.auth.google")
data class GoogleAuthProperties(
    val clientIds: List<String> = emptyList(),
)
