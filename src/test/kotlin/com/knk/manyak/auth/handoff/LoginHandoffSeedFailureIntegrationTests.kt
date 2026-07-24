package com.knk.manyak.auth.handoff

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.GoogleIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 회원 체험 시드가 실패한 로그인은 핸드오프를 소비하지 않아야 한다(스펙 §4-3-7 재시도 보장).
 *
 * 시드는 Redis 장애 시 `false`를 돌려주고 `member_trial_seeded_at`을 NULL로 남겨 다음 로그인이 재시도한다.
 * 그런데 그 로그인이 핸드오프를 소비해 버리면 보관하던 원본 디바이스 ID가 지워져(보관 규칙), 재시도는
 * 외부 브라우저 디바이스로 시드하게 된다 — 게스트 사용량이 리셋되거나 소진 상태로 잘못 확정된다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginHandoffSeedFailureIntegrationTests {

    @TestConfiguration
    class FailingSeedConfig {
        @Bean
        @Primary
        fun fakeGoogleIdTokenVerifier(): GoogleIdTokenVerifier =
            GoogleIdTokenVerifier { idToken -> SocialUserInfo(providerUserId = idToken, name = "테스터") }

        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()

        /** 시드만 실패(Redis 장애)로 흉내 낸다. 디바이스 헤더 검증은 실제 동작을 유지해야 생성이 통과한다. */
        @Bean
        @Primary
        fun failingGuestTrialLimitService(): GuestTrialLimitService {
            val stub = mock(GuestTrialLimitService::class.java)
            `when`(stub.requireDeviceId(anyString())).thenAnswer { it.getArgument<String>(0) }
            `when`(stub.snapshotTrialAtSignup(anyLong(), anyString())).thenReturn(false)
            return stub
        }
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var loginHandoffService: LoginHandoffService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `시드가 실패하면 핸드오프를 소비하지 않고 원본 디바이스 ID를 남긴다`() {
        val created = createHandoff()

        restTestClient.post().uri("/api/v1/auth/login/google")
            .header(RequestCorrelationFilter.HEADER_DEVICE_ID, "device-external-browser")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("idToken" to "google-user-seed-fail", "handoffCode" to created.handoffCode))
            .exchange()
            .expectStatus().isOk

        // 로그인 자체는 성공하고 미시드로 남아 다음 로그인이 재시도한다.
        assertThat(userRepository.findAll().single().memberTrialSeededAt).isNull()

        val handoff = loginHandoffService.find(created.handoffCode)
        assertThat(handoff?.status).isEqualTo(LoginHandoffStatus.PENDING)
        assertThat(handoff?.deviceId)
            .`as`("재시도가 원본 디바이스로 시드할 수 있어야 한다")
            .isEqualTo(IN_APP_DEVICE_ID)
    }

    private fun createHandoff(): LoginHandoffCreateResponse = restTestClient.post().uri("/api/v1/auth/handoffs")
        .header(RequestCorrelationFilter.HEADER_DEVICE_ID, IN_APP_DEVICE_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            mapOf(
                "storyIds" to emptyList<String>(),
                "chatIds" to emptyList<String>(),
                "callbackPath" to "/stories",
                "sourceApp" to "instagram",
            ),
        )
        .exchange()
        .expectStatus().isCreated
        .expectBody(LoginHandoffCreateResponse::class.java)
        .returnResult()
        .responseBody!!

    private companion object {
        const val IN_APP_DEVICE_ID = "device-in-app-seed-fail"
    }
}
