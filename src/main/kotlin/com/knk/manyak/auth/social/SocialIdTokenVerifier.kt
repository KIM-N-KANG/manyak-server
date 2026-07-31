package com.knk.manyak.auth.social

/**
 * 소셜 provider가 발급한 ID 토큰을 검증하고 사용자 정보를 추출하는 포트.
 *
 * 구현은 토큰의 서명(provider JWK), issuer, audience(client-id), 만료를 검증한다.
 * 검증에 실패하면 401([org.springframework.web.server.ResponseStatusException])을 던진다.
 * 인스턴스는 provider별로 하나이며(검증 파라미터가 다르다), 배선은
 * [com.knk.manyak.auth.config.AuthConfig]가 provider → 검증기 맵으로 등록한다.
 * (SAM 인터페이스로 두어 테스트에서 가짜 구현을 람다로 주입할 수 있다.)
 */
fun interface SocialIdTokenVerifier {
    fun verify(idToken: String): SocialUserInfo
}
