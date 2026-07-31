package com.knk.manyak.auth.social

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Kakao 로그인 설정. `manyak.auth.kakao` 프리픽스로 바인딩된다.
 *
 * - [clientIds]: 허용할 카카오 REST API 키 목록. Kakao ID 토큰의 `aud`가 이 중 하나와 일치해야 통과한다.
 *   콤마 구분 환경변수(MANYAK_KAKAO_CLIENT_IDS)로 주입하며, 운영 값은 하드코딩하지 않는다(미주입 시 빈 목록 → 모든 토큰 거부).
 *   **운영에는 단일 카카오 앱의 키 1개만 넣는다**(스펙 §4-5): 카카오 `sub`는 앱별(pairwise)이라 서로 다른 앱의 키를
 *   함께 허용하면 앱 안에서만 유일한 `sub`가 앱 간에 충돌해 다른 사람이 같은 계정으로 오귀속될 수 있다.
 *   목록 형태는 Google 계약과의 표기 통일이다.
 */
@ConfigurationProperties(prefix = "manyak.auth.kakao")
data class KakaoAuthProperties(
    val clientIds: List<String> = emptyList(),
)
