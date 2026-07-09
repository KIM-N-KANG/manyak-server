package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.social.GoogleIdTokenVerifier
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
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
        fun fakeGoogleIdTokenVerifier(): GoogleIdTokenVerifier =
            GoogleIdTokenVerifier { idToken -> SocialUserInfo(providerUserId = idToken) }

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
}
