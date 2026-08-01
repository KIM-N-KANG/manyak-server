package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.social.SocialIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 서버 분석 이벤트(B1, KNK-514) 호출부 배선을 end-to-end로 검증한다. 실제 Spring 컨텍스트에서 발행 싱크를
 * 이벤트를 캡처만 하는 [CapturingPublisher](@Primary)로 대체해, 각 API가 올바른 이벤트명·프로퍼티·식별자로 발행하는지 본다.
 *
 * 피드백·로그인은 요청 스레드에서 동기 발행되므로 exchange() 직후 캡처를 단언할 수 있다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerAnalyticsWiringIntegrationTests {

    class CapturingPublisher : AnalyticsEventPublisher {
        val events = CopyOnWriteArrayList<AnalyticsEvent>()
        override fun publish(event: AnalyticsEvent) {
            events.add(event)
        }

        fun ofType(eventType: String) = events.filter { it.eventType == eventType }
    }

    @TestConfiguration
    class CapturingConfig {
        @Bean
        @Primary
        fun capturingPublisher(): CapturingPublisher = CapturingPublisher()

        @Bean
        @Primary
        fun fakeSocialIdTokenVerifiers(): Map<SocialProvider, SocialIdTokenVerifier> {
            val verifier = SocialIdTokenVerifier { idToken ->
                if (idToken == "invalid") {
                    throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 ID 토큰입니다.")
                }
                // 실제 검증기는 항상 iat를 채운다. 계정 연동 재인증이 신선도를 fail-closed로 보므로 여기서도 채운다.
                SocialUserInfo(providerUserId = idToken, issuedAt = Instant.now())
            }
            return mapOf(SocialProvider.GOOGLE to verifier, SocialProvider.KAKAO to verifier)
        }

        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var capturingPublisher: CapturingPublisher
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        capturingPublisher.events.clear()
    }

    @Test
    fun `피드백 제출 성공은 server_feedback 이벤트를 게스트 식별로 발행한다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"배선 검증 피드백"}""")
            .exchange()
            .expectStatus().is2xxSuccessful

        val event = capturingPublisher.ofType("server_feedback_submission_processed_succeeded").single()
        // 비로그인 요청이라 user_id 없이 게스트(device_id)로 귀속되고 is_logged_in=false다.
        assertThat(event.userId).isNull()
        assertThat(event.deviceId).isNotNull()
        assertThat(event.eventProperties["is_logged_in"]).isEqualTo(false)
    }

    @Test
    fun `구글 로그인 신규 가입 성공은 server_login 이벤트를 회원 식별로 is_new_user true와 함께 발행한다`() {
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"wiring-new-user-sub"}""")
            .exchange()
            .expectStatus().isOk

        val event = capturingPublisher.ofType("server_login_googleLogin_processed_succeeded").single()
        // 로그인 성공은 회원 public_id로 귀속하고 is_new_user=true(find-or-create에서 신규 생성)다.
        assertThat(event.userId).isNotBlank()
        assertThat(event.eventProperties["is_new_user"]).isEqualTo(true)
        assertThat(event.eventProperties["is_logged_in"]).isEqualTo(true)
    }

    @Test
    fun `이미 가입된 계정 재로그인은 is_new_user false로 발행한다`() {
        val body = """{"idToken":"wiring-existing-sub"}"""
        // 최초 로그인(신규 생성) 후 캡처를 비우고, 같은 sub로 재로그인하면 기존 계정 재사용이라 is_new_user=false여야 한다.
        restTestClient.post().uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isOk
        capturingPublisher.events.clear()

        restTestClient.post().uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isOk

        val event = capturingPublisher.ofType("server_login_googleLogin_processed_succeeded").single()
        assertThat(event.eventProperties["is_new_user"]).isEqualTo(false)
    }

    @Test
    fun `카카오 로그인 성공은 kakaoLogin 이벤트를 발행한다`() {
        // provider별로 이벤트명이 갈린다(스펙 §6-4-2-8). 기존 googleLogin 이벤트명은 운영 발행 중이라 그대로 둔다.
        restTestClient.post()
            .uri("/api/v1/auth/login/kakao")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"wiring-kakao-sub"}""")
            .exchange()
            .expectStatus().isOk

        val event = capturingPublisher.ofType("server_login_kakaoLogin_processed_succeeded").single()
        assertThat(event.userId).isNotBlank()
        assertThat(event.eventProperties["is_new_user"]).isEqualTo(true)
        // 구글 이벤트로 새어 나가지 않아야 한다.
        assertThat(capturingPublisher.ofType("server_login_googleLogin_processed_succeeded")).isEmpty()
    }

    @Test
    fun `계정 연동 성공은 socialLink 이벤트를 provider 소문자로 발행한다`() {
        // 재인증(구글) → 링크 코드 → 카카오 연동. 실제 배선에서 이벤트가 나가는지 본다(KNK-739).
        val access = restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"wiring-link-google"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["accessToken"] as String
        capturingPublisher.events.clear()

        val linkCode = restTestClient.post()
            .uri("/api/v1/auth/links/reauth")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $access")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"provider":"GOOGLE","idToken":"wiring-link-google"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(Map::class.java).returnResult().responseBody!!["linkCode"] as String

        restTestClient.post()
            .uri("/api/v1/auth/links/kakao")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $access")
            .header("X-Manyak-Link-Code", linkCode)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"wiring-link-kakao"}""")
            .exchange()
            .expectStatus().isCreated

        val event = capturingPublisher.ofType("server_link_socialLink_processed_succeeded").single()
        assertThat(event.userId).isNotBlank()
        assertThat(event.eventProperties["provider"]).isEqualTo("kakao")
        assertThat(event.eventProperties).doesNotContainKey("error_type")
        // 재인증 단계는 별도 이벤트를 만들지 않는다(연동 이벤트 2종만 정의됨).
        assertThat(capturingPublisher.ofType("server_link_socialLink_processed_failed")).isEmpty()
    }

    @Test
    fun `계정 연동 실패는 error_type validation으로 발행한다`() {
        val access = restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"wiring-link-fail-google"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["accessToken"] as String
        capturingPublisher.events.clear()

        // 링크 코드 없이 연동을 시도하면 403이고 실패 이벤트가 나간다.
        restTestClient.post()
            .uri("/api/v1/auth/links/kakao")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $access")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"wiring-link-fail-kakao"}""")
            .exchange()
            .expectStatus().isForbidden

        val event = capturingPublisher.ofType("server_link_socialLink_processed_failed").single()
        assertThat(event.eventProperties["provider"]).isEqualTo("kakao")
        assertThat(event.eventProperties["error_type"]).isEqualTo("validation")
    }

    @Test
    fun `카카오 로그인 검증 실패는 kakaoLogin 실패 이벤트를 발행한다`() {
        restTestClient.post()
            .uri("/api/v1/auth/login/kakao")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"invalid"}""")
            .exchange()
            .expectStatus().isUnauthorized

        val event = capturingPublisher.ofType("server_login_kakaoLogin_processed_failed").single()
        // 아직 회원이 없어 게스트 식별로 발행되고, 서명·audience 실패는 validation으로 분류한다.
        assertThat(event.userId).isNull()
        assertThat(event.eventProperties["error_type"]).isEqualTo("validation")
    }
}
