package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.user.service.UserWithdrawalService
import com.knk.manyak.invite.service.InviteService
import com.knk.manyak.auth.jwt.JwtTokenProvider
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.entity.CreditReason
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var withdrawals: UserWithdrawalService
    @Autowired private lateinit var invites: InviteService
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var credits: CreditWalletService
    @Autowired private lateinit var policies: CreditPolicyService

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
    private fun member(status: UserStatus = UserStatus.ACTIVE,
                       code: String? = null) = users.save(User(
        nickname = "회원${java.util.UUID.randomUUID()}", status = status, inviteCode = code,
        createdAt = Instant.now().minusSeconds(3 * 86400 + 3600),
    ))

    @Test
    fun `탈퇴 성공은 활성 정지 모두 커밋 후 발행하고 롤백에서는 발행하지 않는다`() {
        for (status in listOf(UserStatus.ACTIVE, UserStatus.SUSPENDED)) {
            val user = member(status)
            capturingPublisher.events.clear()
            TransactionTemplate(transactions).executeWithoutResult {
                withdrawals.withdraw(user.id)
                assertThat(capturingPublisher.events).isEmpty()
            }
            val event = capturingPublisher.ofType("server_account_withdrawal_processed_succeeded").single()
            assertThat(event.userId).isEqualTo(user.publicId.toString())
            assertThat(event.eventProperties).containsEntry("account_age_days", 3L)
                .containsEntry("was_suspended", status == UserStatus.SUSPENDED)
        }
        val user = member()
        capturingPublisher.events.clear()
        TransactionTemplate(transactions).executeWithoutResult {
            withdrawals.withdraw(user.id)
            it.setRollbackOnly()
        }
        assertThat(capturingPublisher.events).isEmpty()
        assertThat(users.findById(user.id).orElseThrow().status).isEqualTo(UserStatus.ACTIVE)
    }

    @Test
    fun `초대 성공과 월 상한은 커밋 후 기록하고 롤백 성공은 기록하지 않는다`() {
        val inviter = member(code = "ABCD2345")
        val redeemer = member()
        TransactionTemplate(transactions).executeWithoutResult {
            invites.redeem(redeemer.id, "ABCD2345")
            assertThat(capturingPublisher.events).isEmpty()
        }
        assertThat(capturingPublisher.ofType("server_invite_redeem_processed_succeeded").single().eventProperties)
            .containsEntry("inviter_capped", false)

        capturingPublisher.events.clear()
        val rollbackMember = member()
        TransactionTemplate(transactions).executeWithoutResult {
            invites.redeem(rollbackMember.id, "ABCD2345")
            it.setRollbackOnly()
        }
        assertThat(capturingPublisher.events).isEmpty()
        assertThat(users.findById(rollbackMember.id).orElseThrow().inviterUserId).isNull()

        val cap = policies.amountOf(CreditPolicyKey.INVITE_MONTHLY_CAP).toInt()
        repeat(cap) { index ->
            credits.reward(inviter.id, 1L, CreditReason.INVITE_REWARD,
                "invite:${inviter.id}:synthetic$index:${inviter.id}")
        }
        val cappedMember = member()
        invites.redeem(cappedMember.id, "ABCD2345")
        val event = capturingPublisher.ofType("server_invite_redeem_processed_succeeded").single()
        assertThat(event.userId).isEqualTo(cappedMember.publicId.toString())
        assertThat(event.eventProperties).containsEntry("inviter_capped", true)
        assertThat(credits.balanceOf(cappedMember.id)).isPositive()
    }

    @Test
    fun `초대 거절 사유 7종은 자격 판정 시 발행한다`() {
        val older = member(code = "ABCD2345")
        val target = member(code = "BCDE2345")
        val withdrawn = member(UserStatus.DELETED, "CDEF2345")
        val suspended = member(UserStatus.SUSPENDED, "DEFG2345")
        val newer = member(code = "EFGH2345")
        val cases = listOf("bad" to "invalid_format", "ZZZZ9999" to "not_found", "BCDE2345" to "self_code",
            withdrawn.inviteCode!! to "inviter_withdrawn", suspended.inviteCode!! to "inviter_unavailable",
            newer.inviteCode!! to "inviter_newer")
        for ((code, reason) in cases) {
            capturingPublisher.events.clear()
            assertThatThrownBy { invites.redeem(target.id, code) }
                .isInstanceOf(ResponseStatusException::class.java)
            val event = capturingPublisher.ofType("server_invite_redeem_processed_failed").single()
            assertThat(event.userId).isEqualTo(target.publicId.toString())
            assertThat(event.eventProperties).containsEntry("error_type", "validation").containsEntry("reason", reason)
        }
        invites.redeem(target.id, older.inviteCode!!)
        capturingPublisher.events.clear()
        assertThatThrownBy { invites.redeem(target.id, older.inviteCode!!) }
            .isInstanceOf(ResponseStatusException::class.java)
        assertThat(capturingPublisher.ofType("server_invite_redeem_processed_failed").single().eventProperties)
            .containsEntry("reason", "already_redeemed")
    }

    @Test
    fun `요청 형식 검증과 인증 거절은 초대 이벤트 집계에서 제외한다`() {
        val user = member()
        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${jwt.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON).body("""{"code":""}""")
            .exchange().expectStatus().isBadRequest
        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .contentType(MediaType.APPLICATION_JSON).body("""{"code":"ABCD2345"}""")
            .exchange().expectStatus().isUnauthorized
        assertThat(capturingPublisher.events).isEmpty()
    }

    @Test
    fun `동의 완료 재가입에서만 is_rejoined가 true이고 후속 로그인은 false다`() {
        fun start() = restTestClient.post().uri("/api/v1/auth/social/google")
            .contentType(MediaType.APPLICATION_JSON).body("""{"idToken":"rejoin-analytics"}""")
            .exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!!
        fun complete(code: String) = restTestClient.post().uri("/api/v1/auth/social/complete")
            .header("X-Manyak-Consent-Token", code).contentType(MediaType.APPLICATION_JSON)
            .body("""{"terms":"v1.4","privacy":"v1.7","age14":"1"}""")
            .exchange().expectStatus().isOk
        val initial = start()
        assertThat(capturingPublisher.events).isEmpty()
        complete(initial["consentToken"] as String)
        val first = capturingPublisher.ofType("server_login_googleLogin_processed_succeeded").single()
        assertThat(first.eventProperties).containsEntry("is_rejoined", false)
        withdrawals.withdraw(users.findByPublicId(java.util.UUID.fromString(first.userId))!!.id)
        capturingPublisher.events.clear()
        val pending = start()
        assertThat(capturingPublisher.events).isEmpty()
        complete(pending["consentToken"] as String)
        assertThat(capturingPublisher.ofType("server_login_googleLogin_processed_succeeded").single().eventProperties)
            .containsEntry("is_new_user", true).containsEntry("is_rejoined", true)
        capturingPublisher.events.clear()
        assertThat(start()["status"]).isEqualTo("COMPLETED")
        assertThat(capturingPublisher.ofType("server_login_googleLogin_processed_succeeded").single().eventProperties)
            .containsEntry("is_new_user", false).containsEntry("is_rejoined", false)
    }

}
