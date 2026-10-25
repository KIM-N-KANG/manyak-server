package com.knk.manyak.push.delivery

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.internal.shared-secret=delivery-test-secret"])
class PushDeliveryIntegrationTests {
    @Autowired lateinit var client: RestTestClient
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var socials: SocialAccountRepository
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var campaigns: PushCampaignRepository
    private lateinit var token: String
    private lateinit var user: User

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        user = users.save(User(nickname = "관리자"))
        socials.save(SocialAccount(userId = user.id, provider = SocialProvider.GOOGLE, providerUserId = "delivery-admin"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", user.id)
        token = jwt.issueAdminToken(user.publicId)
    }

    private fun body(id: String, outcome: String = "DELIVERED", delivered: Int = 2, failed: Int = 0,
        at: String = "2026-10-21T00:00:00Z", type: String = "STORY_COMPLETED", campaign: UUID? = null,
        recipient: UUID = user.publicId): Map<String, Any?> = mapOf(
        "messageId" to id, "type" to type, "kind" to if (type == "PROMOTION") "MARKETING" else "SERVICE",
        "recipientId" to recipient, "campaignId" to campaign, "outcome" to outcome, "reason" to "TEST",
        "deliveredDevices" to delivered, "failedDevices" to failed, "completedAt" to at)

    private fun report(body: Any, secret: String? = "delivery-test-secret"): RestTestClient.ResponseSpec {
        val req = client.post().uri("/internal/push-deliveries").contentType(MediaType.APPLICATION_JSON)
        secret?.let { req.header("X-Manyak-Internal-Secret", it) }
        return req.body(body).exchange()
    }
    private fun stats(query: String = "from=2026-10-20&to=2026-10-21") = client.get()
        .uri("/api/v1/admin/push/delivery-stats?$query").header("Authorization", "Bearer $token").exchange()

    @Test fun `이프 만료 안내 결과를 SERVICE로 수용하고 관리자 종류 필터로 조회한다`() {
        report(body("credit-expiry:recipient:2026-10-25", type = "CREDIT_EXPIRY_REMINDER")).expectStatus().isNoContent
        stats("from=2026-10-21&to=2026-10-21&type=CREDIT_EXPIRY_REMINDER").expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.items[0].type").isEqualTo("CREDIT_EXPIRY_REMINDER")
            .jsonPath("$.items[0].kind").isEqualTo("SERVICE")
    }

    @Test fun `도달 미도달 폐기와 없는 회원을 보존하고 재보고는 첫 행을 유지한다`() {
        report(body("a")).expectStatus().isNoContent
        report(body("a", delivered = 9)).expectStatus().isNoContent
        report(body("b", "UNDELIVERED", 0, 2)).expectStatus().isNoContent
        report(body("c", "DISCARDED", 0, recipient = UUID.randomUUID()) + ("reason" to "EXPIRED")).expectStatus().isNoContent
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_delivery_results", Int::class.java)).isEqualTo(3)
        assertThat(jdbc.queryForObject("SELECT delivered_devices FROM push_delivery_results WHERE message_id='a'", Int::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForMap("SELECT * FROM push_delivery_results WHERE message_id='c'")).containsEntry("user_id", null).containsEntry("reason", "EXPIRED")
    }

    @Test fun `동시 재보고는 둘 다 성공하고 한 행만 보존한다`() {
        val futures = (1..2).map { CompletableFuture.runAsync { report(body("concurrent")).expectStatus().isNoContent } }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_delivery_results", Int::class.java)).isEqualTo(1)
    }

    @Test fun `필수 필드 형식 음수 결과 불일치 없는 캠페인은 400이다`() {
        val valid = body("invalid")
        for (field in valid.keys - "campaignId") report(valid - field).expectStatus().isBadRequest
        for (change in listOf(mapOf("deliveredDevices" to -1), mapOf("failedDevices" to -1),
            mapOf("outcome" to "DISCARDED"), mapOf("deliveredDevices" to 0), mapOf("type" to "UNKNOWN"),
            mapOf("kind" to "UNKNOWN"), mapOf("messageId" to " "), mapOf("messageId" to "x".repeat(256)),
            mapOf("reason" to "x".repeat(41)), mapOf("recipientId" to "invalid"),
            mapOf("campaignId" to UUID.randomUUID()), mapOf("completedAt" to "invalid"))) {
            report(valid + change).expectStatus().isBadRequest
        }
    }

    @Test fun `내부 인증은 누락과 오답을 거절하고 관리자 토큰으로 대체할 수 없다`() {
        report(body("a"), null).expectStatus().isUnauthorized
        report(body("a"), "wrong").expectStatus().isUnauthorized
        client.post().uri("/internal/push-deliveries").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).body(body("a")).exchange().expectStatus().isUnauthorized
    }

    @Test fun `종류 날짜 집계는 KST 경계 정렬 필터와 메시지 기준 회원 수를 지킨다`() {
        report(body("before", at = "2026-10-19T14:59:59Z")).expectStatus().isNoContent
        report(body("day20", at = "2026-10-19T15:00:00Z")).expectStatus().isNoContent
        report(body("day21a", at = "2026-10-20T15:00:00Z")).expectStatus().isNoContent
        report(body("day21b", "UNDELIVERED", 0, 2)).expectStatus().isNoContent
        report(body("day21c", "DISCARDED", 0)).expectStatus().isNoContent
        report(body("promo", type = "PROMOTION")).expectStatus().isNoContent
        report(body("after", at = "2026-10-21T15:00:00Z")).expectStatus().isNoContent
        stats().expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(3)
            .jsonPath("$.items[0].type").isEqualTo("PROMOTION")
            .jsonPath("$.items[1].date").isEqualTo("2026-10-21")
            .jsonPath("$.items[1].requested").isEqualTo(3)
            .jsonPath("$.items[1].deliveredMembers").isEqualTo(1)
            .jsonPath("$.items[1].deliveredDevices").isEqualTo(2)
            .jsonPath("$.items[1].failedDevices").isEqualTo(2)
            .jsonPath("$.items[2].date").isEqualTo("2026-10-20")
        stats("from=2026-10-20&to=2026-10-21&type=PROMOTION").expectStatus().isOk
            .expectBody().jsonPath("$.items.length()").isEqualTo(1)
        stats("to=2026-10-21").expectStatus().isOk.expectBody().jsonPath("$.from").isEqualTo("2026-10-15")
        stats("from=2020-01-01&to=2020-12-31").expectStatus().isOk
    }

    @Test fun `통계 kind는 단독 또는 type과 AND로 필터하고 생략하면 전체를 반환한다`() {
        report(body("kind-service")).expectStatus().isNoContent
        report(body("kind-marketing", type = "PROMOTION")).expectStatus().isNoContent
        val period = "from=2026-10-21&to=2026-10-21"
        stats(period).expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(2)
        stats("$period&kind=SERVICE").expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.items[0].type").isEqualTo("STORY_COMPLETED")
            .jsonPath("$.items[0].kind").isEqualTo("SERVICE").jsonPath("$.items[0].requested").isEqualTo(1)
        stats("$period&kind=MARKETING").expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.items[0].type").isEqualTo("PROMOTION")
            .jsonPath("$.items[0].kind").isEqualTo("MARKETING")
        stats("$period&type=PROMOTION&kind=MARKETING").expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.items[0].type").isEqualTo("PROMOTION")
        stats("$period&type=PROMOTION&kind=SERVICE").expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(0)
    }

    @Test fun `통계는 지원하지 않는 kind를 400으로 거절한다`() {
        for (kind in listOf("UNKNOWN", "service", "MARKET")) {
            stats("kind=$kind").expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST")
        }
    }

    @Test fun `통계는 역전 기간 367일 날짜 오류 지원하지 않는 종류를 거절한다`() {
        for (q in listOf("from=2026-10-22&to=2026-10-21", "from=2020-01-01&to=2021-01-01",
            "from=bad", "from=2026/10/21", "type=UNKNOWN")) stats(q).expectStatus().isBadRequest
    }
    @Autowired lateinit var deliveries: com.knk.manyak.push.service.PushDeliveryService
    @Autowired lateinit var tokens: com.knk.manyak.push.repository.DevicePushTokenRepository

    @Test fun `캠페인 참조와 집계는 도달 행 수와 기기 합계를 반환하고 없는 결과는 0이다`() {
        val campaign = campaigns.save(PushCampaign(title = "캠페인", body = "본문", scheduledAt = Instant.now()))
        assertThat(deliveries.campaignStats(campaign.id)).isEqualTo(com.knk.manyak.push.dto.CampaignDeliveryStats(0, 0, 0))
        report(body("c1", delivered = 2, type = "PROMOTION", campaign = campaign.publicId)).expectStatus().isNoContent
        report(body("c2", delivered = 1, type = "PROMOTION", campaign = campaign.publicId)).expectStatus().isNoContent
        report(body("c3", "DISCARDED", 0, type = "PROMOTION", campaign = campaign.publicId)).expectStatus().isNoContent
        assertThat(deliveries.campaignStats(campaign.id)).isEqualTo(com.knk.manyak.push.dto.CampaignDeliveryStats(2, 3, 0))
    }

    @Test fun `로컬 완성 리스너는 메시지 ID와 FCM 미설정 폐기 결과를 기록한다`() {
        val sender = com.knk.manyak.push.service.FcmPushSender(null, tokens, users,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry(), deliveries = deliveries)
        val listener = com.knk.manyak.push.event.StoryCompletionPushListener(users, sender, java.util.concurrent.Executor { it.run() })
        val requestId = UUID.randomUUID()
        listener.onStoryCompleted(com.knk.manyak.story.event.StoryCompletedEvent(user.id, requestId, UUID.randomUUID().toString(), "완성"))
        val row = jdbc.queryForMap("SELECT * FROM push_delivery_results")
        assertThat(row).containsEntry("message_id", "story-completed:$requestId")
            .containsEntry("outcome", "DISCARDED").containsEntry("reason", "FCM_DISABLED").containsEntry("delivered_devices", 0)
    }

    @Test fun `로컬 발송은 무효 토큰을 실패 기기에서 빼고 부분 성공을 기록한다`() {
        val messaging = org.mockito.Mockito.mock(com.google.firebase.messaging.FirebaseMessaging::class.java)
        val sender = com.knk.manyak.push.service.FcmPushSender(messaging, tokens, users,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry(), deliveries = deliveries)
        repeat(3) { tokens.save(com.knk.manyak.push.entity.DevicePushToken(userId = user.id,
            token = "local-$it", platform = com.knk.manyak.push.entity.PushPlatform.ANDROID)) }
        val invalid = org.mockito.Mockito.mock(com.google.firebase.messaging.FirebaseMessagingException::class.java)
        org.mockito.Mockito.`when`(invalid.messagingErrorCode).thenReturn(com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED)
        org.mockito.Mockito.`when`(messaging.send(org.mockito.ArgumentMatchers.any())).thenReturn("accepted")
            .thenThrow(invalid).thenThrow(IllegalArgumentException("permanent"))
        sender.sendToUser(user.id, mapOf("type" to "STORY_COMPLETED", "title" to "완성"), messageId = "local-partial")
        assertThat(jdbc.queryForMap("SELECT * FROM push_delivery_results")).containsEntry("outcome", "DELIVERED")
            .containsEntry("delivered_devices", 1).containsEntry("failed_devices", 1).containsEntry("reason", "PARTIAL_FAILURE")
        assertThat(tokens.count()).isEqualTo(2)
    }

    @Test fun `로컬 자격 거절과 만료는 발송 없이 폐기로 기록한다`() {
        user.servicePushEnabled = false
        users.saveAndFlush(user)
        val sender = com.knk.manyak.push.service.FcmPushSender(null, tokens, users,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry(), deliveries = deliveries)
        val listener = com.knk.manyak.push.event.StoryCompletionPushListener(users, sender, java.util.concurrent.Executor { it.run() })
        listener.onStoryCompleted(com.knk.manyak.story.event.StoryCompletedEvent(user.id, UUID.randomUUID(), UUID.randomUUID().toString(), "완성"))
        sender.sendToUser(user.id, mapOf("type" to "ATTENDANCE_REMINDER"),
            expiresAt = Instant.EPOCH, messageId = "expired-local")
        val rows = jdbc.queryForList("SELECT * FROM push_delivery_results ORDER BY id")
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it["outcome"] }).containsOnly("DISCARDED")
        assertThat(rows.map { it["reason"] }).containsExactly("SERVICE_PUSH_DISABLED", "EXPIRED")
    }

    @Test fun `로컬 결과 저장 실패는 발송 경로에 전파되지 않는다`() {
        val recording = org.mockito.Mockito.mock(com.knk.manyak.push.service.PushDeliveryService::class.java)
        org.mockito.Mockito.doThrow(IllegalStateException("report unavailable")).`when`(recording)
            .record(org.mockito.ArgumentMatchers.any(com.knk.manyak.push.dto.PushDeliveryReportRequest::class.java)
                ?: com.knk.manyak.push.dto.PushDeliveryReportRequest("matcher", com.knk.manyak.push.dto.PushDeliveryType.STORY_COMPLETED,
                    com.knk.manyak.push.dto.PushKind.SERVICE, user.publicId, outcome = com.knk.manyak.push.dto.PushDeliveryOutcome.DISCARDED,
                    reason = "TEST", deliveredDevices = 0, failedDevices = 0, completedAt = Instant.now()))
        val sender = com.knk.manyak.push.service.FcmPushSender(null, tokens, users,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry(), deliveries = recording)
        org.assertj.core.api.Assertions.assertThatCode {
            sender.sendToUser(user.id, mapOf("type" to "STORY_COMPLETED"), messageId = "record-failure")
        }.doesNotThrowAnyException()
        assertThat(org.mockito.Mockito.mockingDetails(recording).invocations.count { it.method.name == "record" }).isEqualTo(1)
    }

}
