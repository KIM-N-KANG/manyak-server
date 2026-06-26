package com.knk.manyak.auth.social

/**
 * Google ID 토큰을 검증하고 사용자 정보를 추출하는 포트.
 *
 * 구현은 토큰의 서명(Google JWK), issuer, audience(client-id), 만료를 검증한다.
 * 검증에 실패하면 401([org.springframework.web.server.ResponseStatusException])을 던진다.
 * (SAM 인터페이스로 두어 테스트에서 가짜 구현을 람다로 주입할 수 있다.)
 */
fun interface GoogleIdTokenVerifier {
    fun verify(idToken: String): SocialUserInfo
}
