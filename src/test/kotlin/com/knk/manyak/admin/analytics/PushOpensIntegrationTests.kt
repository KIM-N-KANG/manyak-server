package com.knk.manyak.admin.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.support.DatabaseCleaner
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@AutoConfigureRestTestClient
@Import(CampaignTestClock::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushOpensIntegrationTests {
    companion object {
        private val server = MockWebServer().apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("manyak.analytics.amplitude.api-key") { "mock" }
            registry.add("manyak.analytics.amplitude.secret-key") { "mock" }
            registry.add("manyak.analytics.amplitude.dashboard-base-url") { server.url("/").toString() }
            registry.add("manyak.admin.push-opens.measured-from") { "2026-10-21" }
        }
        @JvmStatic @AfterAll fun stop() { server.shutdown() }
    }
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var campaigns: PushCampaignRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val mapper = ObjectMapper()

    @Test fun `측정 설정 API는 도달 DB와 일별 열람 저장소를 결합하고 공개 식별자만 응답한다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        val campaign = campaigns.saveAndFlush(PushCampaign(title = "원문 제목", body = "본문", scheduledAt = Instant.EPOCH,
            status = PushCampaignStatus.SENT, startedAt = Instant.parse("2026-10-22T00:00:00Z"), deepLinkIncluded = true))
        jdbc.update("""INSERT INTO push_delivery_results(message_id,campaign_id,type,kind,user_id,outcome,reason,delivered_devices,failed_devices,completed_at)
            VALUES ('notice-fixture',?,'PROMOTION','MARKETING',?,'DELIVERED','fixture',1,0,'2026-10-22T01:00:00Z')""", campaign.id, admin.id)
        for (date in listOf("2026-10-22", "2026-10-23")) {
            jdbc.update("""INSERT INTO push_delivery_results(message_id,type,kind,user_id,outcome,reason,delivered_devices,failed_devices,completed_at)
                VALUES (?,'CREDIT_EXPIRY_REMINDER','SERVICE',?,'DELIVERED','fixture',1,0,?)""",
                "credit-expiry-fixture:$date", admin.id, Instant.parse("${date}T01:00:00Z"))
        }
        server.enqueue(MockResponse().setBody("""{"data":{"xValues":["2026-10-22","2026-10-23"],"seriesLabels":[[0,"${campaign.publicId}"],[0,"attendance_reminder"],[0,"credit_expiry_reminder"]],"series":[[1,2],[0,2],[1,2]]}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"xValues":["2026-10-22","2026-10-23"],"seriesLabels":[],"series":[]}}"""))
        val token = jwt.issueAdminToken(admin.publicId)
        fun get() = client.get().uri("/api/v1/admin/campaigns/push-opens?from=2026-10-22&to=2026-10-23")
            .header("Authorization", "Bearer $token").exchange()
        val body = mapper.readTree(get().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        val item = body["campaigns"].single()
        assertThat(item["campaignId"].asText()).isEqualTo(campaign.publicId.toString())
        assertThat(item.has("id")).isFalse()
        assertThat(item["title"].asText()).isEqualTo("원문 제목")
        assertThat(item["deliveredMembers"].asLong()).isEqualTo(1)
        assertThat(item["openState"].asText()).isEqualTo("MEASURED")
        assertThat(item["pushOpens"].asLong()).isEqualTo(3)
        assertThat(item["pushOpenRate"].asDouble()).isEqualTo(3.0)
        assertThat(body["activityFrom"].asText()).isEqualTo("2026-10-22")
        assertThat(body["activityTypes"].size()).isEqualTo(4)
        assertThat(body["activityTypes"][2]["pushOpens"].asLong()).isEqualTo(2)
        assertThat(body["activityTypes"][2]["pushOpenRate"].isNull).isTrue()
        val expiry = body["activityTypes"].single { it["type"].asText() == "CREDIT_EXPIRY_REMINDER" }
        assertThat(expiry["deliveredMembers"].asLong()).isEqualTo(2)
        assertThat(expiry["openState"].asText()).isEqualTo("MEASURED")
        assertThat(expiry["pushOpens"].asLong()).isEqualTo(3)
        assertThat(expiry["pushOpenRate"].asDouble()).isEqualTo(1.5)
        assertThat(body["pendingDates"].size()).isZero()
        assertThat(body["asOf"].isString).isTrue()
        get().expectStatus().isOk
        client.get().uri("/api/v1/admin/campaigns/push-opens/${campaign.publicId}").header("Authorization", "Bearer $token").exchange()
            .expectStatus().isOk.expectBody().jsonPath("$.from").isEqualTo("2026-10-22").jsonPath("$.to").isEqualTo("2026-10-23")
            .jsonPath("$.pushOpens").isEqualTo(3).jsonPath("$.webPushOpens").isEqualTo(3).jsonPath("$.androidPushOpens").isEmpty()
            .jsonPath("$.pushOpenRate").isEqualTo(3.0)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_analytics_daily_values WHERE metric='push_opens'", Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }
    @Test fun `캠페인 하나 열람은 선점 전과 링크 없음 부재 및 인증을 구분한다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "캠페인 열람 관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        val token = jwt.issueAdminToken(admin.publicId)
        val pending = campaigns.saveAndFlush(PushCampaign(title = "예약", body = "본문", scheduledAt = Instant.EPOCH))
        val noLink = campaigns.saveAndFlush(PushCampaign(title = "링크 없음", body = "본문", scheduledAt = Instant.EPOCH,
            status = PushCampaignStatus.SENT, startedAt = Instant.parse("2026-10-22T00:00:00Z"), deepLinkIncluded = false))
        fun get(id: String, auth: String? = token) = client.get().uri("/api/v1/admin/campaigns/push-opens/$id")
            .apply { auth?.let { header("Authorization", "Bearer $it") } }.exchange()
        val calls = server.requestCount
        get(pending.publicId.toString()).expectStatus().isOk.expectBody().jsonPath("$.openState").isEqualTo("PENDING")
            .jsonPath("$.from").isEmpty().jsonPath("$.to").isEmpty().jsonPath("$.startedAt").isEmpty().jsonPath("$.deliveredMembers").isEqualTo(0)
            .jsonPath("$.androidMeasuredFrom").isEmpty().jsonPath("$.asOf").isEmpty().jsonPath("$.pendingDates.length()").isEqualTo(0)
        get(noLink.publicId.toString()).expectStatus().isOk.expectBody().jsonPath("$.openState").isEqualTo("NO_LINK")
            .jsonPath("$.pushOpens").isEmpty().jsonPath("$.inboxOpens").isEmpty()
        get(java.util.UUID.randomUUID().toString()).expectStatus().isNotFound
        get("invalid-uuid").expectStatus().isBadRequest
        get(pending.publicId.toString(), null).expectStatus().isUnauthorized
        get(pending.publicId.toString(), jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
        assertThat(server.requestCount).isEqualTo(calls)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

}
