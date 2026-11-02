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

    @Autowired private lateinit var stories: com.knk.manyak.story.repository.StoryRepository
    @Autowired private lateinit var chats: com.knk.manyak.chat.repository.StoryChatRepository
    @Autowired private lateinit var messages: com.knk.manyak.chat.repository.StoryMessageRepository

    private fun conversionAuth(): String {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "전환 관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        return jwt.issueAdminToken(admin.publicId)
    }
    private fun conversionCampaign(start: String = "2026-10-22T00:00:00Z", link: Boolean? = true) =
        campaigns.saveAndFlush(PushCampaign(title = "전환", body = "본문", scheduledAt = Instant.EPOCH,
            status = PushCampaignStatus.SENT, startedAt = Instant.parse(start), deepLinkIncluded = link))
    private fun conversionDelivery(user: User?, at: String, campaign: PushCampaign? = null, type: String = "PROMOTION", devices: Int = 1) {
        jdbc.update("""INSERT INTO push_delivery_results(message_id,campaign_id,type,kind,user_id,outcome,reason,delivered_devices,failed_devices,completed_at)
            VALUES (?,?,?,'SERVICE',?,?,'fixture',?,0,?)""", java.util.UUID.randomUUID().toString(), campaign?.id, type, user?.id,
            if (devices > 0) "DELIVERED" else "UNDELIVERED", devices, java.sql.Timestamp.from(Instant.parse(at)))
    }
    private fun conversionMessage(user: User?, at: String, role: String = "USER", deleted: Boolean = false) {
        val story = stories.saveAndFlush(com.knk.manyak.story.entity.Story(title = "전환 스토리", genre = "판타지"))
        val chat = chats.saveAndFlush(com.knk.manyak.chat.entity.StoryChat(storyId = story.id, userId = user?.id,
            deletedAt = if (deleted) Instant.parse(at) else null))
        messages.saveAndFlush(com.knk.manyak.chat.entity.StoryMessage(chatId = chat.id,
            role = com.knk.manyak.chat.entity.MessageRole.valueOf(role), content = "집계에서 읽지 않는 원문", messageOrder = 1, createdAt = Instant.parse(at)))
    }
    private fun conversionGet(path: String, token: String) = mapper.readTree(client.get().uri("/api/v1/admin/campaigns/push-opens$path")
        .header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    private fun emptyOpens(vararg dates: String) {
        repeat(2) { server.enqueue(MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf(
            "xValues" to dates.toList(), "seriesLabels" to emptyList<String>(), "series" to emptyList<Long>()))))) }
    }
    private fun assertConversion(row: tools.jackson.databind.JsonNode, after: Long?, before: Long?, rate: Double?) {
        for ((key, value) in mapOf("chattedMembers" to after, "chattedBeforeMembers" to before)) {
            assertThat(row.has(key)).describedAs(key).isTrue()
            if (value == null) assertThat(row[key].isNull).isTrue() else assertThat(row[key].asLong()).isEqualTo(value)
        }
        assertThat(row.has("chatRate")).isTrue()
        if (rate == null) assertThat(row["chatRate"].isNull).isTrue() else assertThat(row["chatRate"].asDouble()).isEqualTo(rate)
    }

    @Test fun `채팅 전환은 도달 처리 시각 앞뒤 24시간의 USER 메시지를 회원별로 세고 캠페인 단건도 같다`() {
        val token = conversionAuth()
        val campaign = conversionCampaign()
        val members = (1..4).map { users.save(User(nickname = "수신$it")) }
        members.forEach { conversionDelivery(it, "2026-10-22T02:00:00Z", campaign) }
        conversionMessage(members[0], "2026-10-22T10:00:00Z")
        conversionMessage(members[0], "2026-10-22T11:00:00Z") // 한 회원 중복 제거
        conversionMessage(members[1], "2026-10-23T03:00:00Z") // 25시간 뒤 제외
        conversionMessage(members[2], "2026-10-21T20:00:00Z")
        conversionMessage(members[2], "2026-10-22T05:00:00Z")
        conversionMessage(members[3], "2026-10-22T05:00:00Z", "ASSISTANT")
        emptyOpens("2026-10-22", "2026-10-23")
        val row = conversionGet("?from=2026-10-22&to=2026-10-23", token)["campaigns"].single()
        assertConversion(row, 2, 1, 0.5)
        assertConversion(conversionGet("/${campaign.publicId}", token), 2, 1, 0.5)
    }

    @Test fun `채팅 전환은 NO_LINK BEFORE_MEASUREMENT에서도 계산하고 PENDING과 도달 영은 null이다`() {
        val token = conversionAuth()
        val noLink = conversionCampaign(link = false)
        val before = conversionCampaign("2026-10-20T00:00:00Z")
        val pending = campaigns.saveAndFlush(PushCampaign(title = "대기", body = "본문", scheduledAt = Instant.EPOCH))
        val undelivered = conversionCampaign(link = false)
        val member = users.save(User(nickname = "수신자"))
        for (campaign in listOf(noLink, before)) conversionDelivery(member, "2026-10-22T02:00:00Z", campaign)
        conversionDelivery(member, "2026-10-22T02:00:00Z", undelivered, devices = 0)
        conversionMessage(member, "2026-10-22T03:00:00Z")
        val calls = server.requestCount
        for ((campaign, state) in listOf(noLink to "NO_LINK", before to "BEFORE_MEASUREMENT")) {
            val row = conversionGet("/${campaign.publicId}", token)
            assertThat(row["openState"].asText()).isEqualTo(state)
            assertThat(row["pushOpens"].isNull).isTrue()
            assertConversion(row, 1, 0, 1.0)
        }
        assertConversion(conversionGet("/${pending.publicId}", token), null, null, null)
        assertConversion(conversionGet("/${undelivered.publicId}", token), null, null, null)
        assertThat(server.requestCount).isEqualTo(calls)
    }

    @Test fun `활동 알림 채팅 전환은 같은 집계 구간에서 종류 KST 날짜 회원 중복을 빼고 날짜 합을 센다`() {
        val token = conversionAuth()
        val member = users.save(User(nickname = "반복 수신"))
        for (at in listOf("2026-10-22T01:00:00Z", "2026-10-22T02:00:00Z", "2026-10-23T02:00:00Z"))
            conversionDelivery(member, at, type = "ATTENDANCE_REMINDER")
        // 측정 시작 전 활동은 activityFrom=10-21로 잘려 분모와 분자 모두에서 빠진다.
        conversionDelivery(member, "2026-10-20T02:00:00Z", type = "ATTENDANCE_REMINDER")
        conversionMessage(member, "2026-10-20T03:00:00Z")
        conversionMessage(member, "2026-10-22T05:00:00Z")
        conversionDelivery(null, "2026-10-22T02:00:00Z", type = "ATTENDANCE_REMINDER")
        conversionDelivery(member, "2026-10-22T02:00:00Z", type = "STORY_COMPLETED", devices = 0)
        emptyOpens("2026-10-21", "2026-10-22", "2026-10-23")
        val body = conversionGet("?from=2026-10-20&to=2026-10-23", token)
        assertThat(body["activityFrom"].asText()).isEqualTo("2026-10-21")
        val attendance = body["activityTypes"].single { it["type"].asText() == "ATTENDANCE_REMINDER" }
        assertThat(attendance["deliveredMembers"].asLong()).isEqualTo(2)
        assertConversion(attendance, 1, 1, 0.5)
        assertConversion(body["activityTypes"].first(), null, null, null)
        val before = conversionGet("?from=2026-10-20&to=2026-10-20", token)
        assertThat(before["activityFrom"].isNull).isTrue()
        assertConversion(before["activityTypes"].single { it["type"].asText() == "ATTENDANCE_REMINDER" }, 1, 0, 1.0)
    }

    @Test fun `채팅 전환은 타인과 게스트를 제외하고 삭제 채팅도 소유자로 판정하며 매번 다시 센다`() {
        val token = conversionAuth()
        val campaign = conversionCampaign(link = false)
        val recipient = users.save(User(nickname = "도달 회원"))
        val other = users.save(User(nickname = "다른 회원"))
        conversionDelivery(recipient, "2026-10-22T02:00:00Z", campaign)
        conversionMessage(other, "2026-10-22T03:00:00Z")
        conversionMessage(null, "2026-10-22T03:00:00Z")
        assertConversion(conversionGet("/${campaign.publicId}", token), 0, 0, 0.0)
        conversionMessage(recipient, "2026-10-22T03:00:00Z", deleted = true)
        assertConversion(conversionGet("/${campaign.publicId}", token), 1, 0, 1.0)
    }

    @Test fun `캠페인 채팅 전환은 열람 366일 상한과 무관하게 마지막 회차 도달 전체를 센다`() {
        val token = conversionAuth()
        val campaign = conversionCampaign("2025-09-01T00:00:00Z", link = false)
        val member = users.save(User(nickname = "늦은 보고"))
        conversionDelivery(member, "2026-10-22T02:00:00Z", campaign)
        conversionMessage(member, "2026-10-22T03:00:00Z")
        val row = conversionGet("/${campaign.publicId}", token)
        assertThat(row["to"].asText()).isEqualTo("2026-09-01")
        assertConversion(row, 1, 0, 1.0)
    }

    @Test fun `마지막 회차 분모는 과거 null 회원 중복을 제외하고 목록 단건 상세 전환율과 일치한다`() {
        val token = conversionAuth()
        val campaign = conversionCampaign(link = false)
        val old = users.save(User(nickname = "과거 회차"))
        val current = users.save(User(nickname = "현재 회차"))
        conversionDelivery(old, "2026-10-21T02:00:00Z", campaign)
        conversionDelivery(current, "2026-10-22T02:00:00Z", campaign)
        conversionDelivery(null, "2026-10-22T03:00:00Z", campaign)
        conversionDelivery(current, "2026-10-22T04:00:00Z", campaign)
        conversionMessage(current, "2026-10-22T05:00:00Z")
        emptyOpens("2026-10-22")
        val row = conversionGet("?from=2026-10-22&to=2026-10-22", token)["campaigns"].single()
        assertThat(row["deliveredMembers"].asLong()).isEqualTo(1)
        assertConversion(row, 1, 0, 1.0)
        val single = conversionGet("/${campaign.publicId}", token)
        assertThat(single["deliveredMembers"].asLong()).isEqualTo(1)
        assertConversion(single, 1, 0, 1.0)
        for (path in listOf("/api/v1/admin/push/campaigns/${campaign.publicId}", "/api/v1/admin/push/campaigns")) {
            val body = mapper.readTree(client.get().uri(path).header("Authorization", "Bearer $token").exchange()
                .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
            val item = if (body.has("items")) body["items"].single() else body
            assertThat(item["deliveredMembers"].asLong()).isEqualTo(1)
        }
    }

}
