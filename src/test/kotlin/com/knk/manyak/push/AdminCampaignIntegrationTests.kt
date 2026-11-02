package com.knk.manyak.push

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.*
import com.knk.manyak.push.repository.*
import com.knk.manyak.push.service.PushMessageTemplateService
import com.knk.manyak.push.service.PushTemplateKey
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminCampaignIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var campaigns: PushCampaignRepository
    @Autowired private lateinit var templates: PushMessageTemplateRepository
    @Autowired private lateinit var text: PushMessageTemplateService
    private val mapper = ObjectMapper()
    private val root = "/api/v1/admin/push"
    private lateinit var admin: User
    private lateinit var token: String

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        text.refresh()
        admin = users.save(User(nickname = "관리자", servicePushEnabled = false))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun call(method: HttpMethod, path: String, body: Any? = null, auth: String? = token) =
        client.method(method).uri(root + path).contentType(MediaType.APPLICATION_JSON)
            .apply { auth?.let { header("Authorization", "Bearer $it") }; body?.let { body(it) } }.exchange()
    private fun campaignBody() = mutableMapOf<String, Any>("title" to "이벤트 안내", "body" to "새 소식을 확인해 보세요", "reason" to "이벤트")
    private fun create(data: Map<String, Any> = campaignBody()) = mapper.readTree(call(HttpMethod.POST, "/campaigns", data)
        .expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody!!)
    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)!!
    private fun templateBody() = mutableMapOf<String, Any>("title" to "새 출석 문구", "body" to "출석해 주세요", "reason" to "문구 교체")
    private fun templateGet() = mapper.readTree(call(HttpMethod.GET, "/templates/attendance_reminder")
        .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var auditRepository: com.knk.manyak.admin.repository.AdminAuditLogRepository

    @Autowired private lateinit var notices: com.knk.manyak.notice.repository.NoticeRepository

    @Test fun `공지 연결은 태그로 종류를 정하고 첫 시각을 유지하며 감사에 공개 식별자를 남긴다`() {
        for (tag in com.knk.manyak.notice.entity.NoticeTag.entries) {
            val notice = notices.save(com.knk.manyak.notice.entity.Notice(title = "공지", body = "본문", tag = tag,
                status = com.knk.manyak.notice.entity.NoticeStatus.PUBLISHED, createdByUserId = admin.id,
                // DB 정밀도에 맞춰 반올림될 수 있는 나노초 값으로 플랫폼 차이를 재현한다.
                updatedAt = Instant.parse("2026-10-30T01:00:00.123456789Z")))
            val storedUpdatedAt = notices.findById(notice.id).orElseThrow().updatedAt
            val first = create(campaignBody().apply { put("noticeId", notice.publicId.toString()); put("kind", "INVALID") })
            assertThat(first["kind"].asText()).isEqualTo(tag.pushKind.name)
            assertThat(first["noticeId"].asText()).isEqualTo(notice.publicId.toString())
            assertThat(first["failureReason"].isNull).isTrue()
            val linked = notices.findById(notice.id).orElseThrow()
            assertThat(linked.notifiedAt).isNotNull()
            assertThat(linked.updatedAt).isEqualTo(storedUpdatedAt)
            create(campaignBody().apply { put("noticeId", notice.publicId.toString()); put("scheduledAt", Instant.now().plusSeconds(3600).toString()) })
            assertThat(notices.findById(notice.id).orElseThrow().notifiedAt).isEqualTo(linked.notifiedAt)
            assertThat(campaigns.findAll().filter { it.noticeId == notice.id }).hasSize(2).allSatisfy {
                assertThat(it.noticeTag).isEqualTo(tag)
            }
            val raw = jdbc.queryForList("SELECT after_state FROM admin_audit_logs WHERE action='PUSH_CAMPAIGN_CREATED' ORDER BY id DESC").first()["after_state"]!!
            val audit = mapper.readTree(if (raw is ByteArray) String(raw, Charsets.UTF_8) else raw.toString())
            assertThat(audit["noticeId"].asText()).isEqualTo(notice.publicId.toString())
            assertThat(audit.properties().map { it.key }).containsExactlyInAnyOrder("kind", "title", "body", "scheduledAt", "status", "storyId", "noticeId")
        }
        assertThat(count("admin_audit_logs")).isEqualTo(10)
    }

    @Test fun `초안 해제 없는 공지와 두 이동 대상은 예약하지 않는다`() {
        val draft = notices.save(com.knk.manyak.notice.entity.Notice(title = "초안", body = "본문",
            tag = com.knk.manyak.notice.entity.NoticeTag.SERVICE, createdByUserId = admin.id))
        for (id in listOf(draft.publicId, UUID.randomUUID()))
            call(HttpMethod.POST, "/campaigns", campaignBody().apply { put("noticeId", id.toString()) }).expectStatus().isBadRequest
        call(HttpMethod.POST, "/campaigns", campaignBody().apply { put("noticeId", draft.publicId.toString()); put("storyId", UUID.randomUUID().toString()) })
            .expectStatus().isBadRequest
        assertThat(campaigns.count()).isZero()
        assertThat(notices.findById(draft.id).orElseThrow().notifiedAt).isNull()
        assertThat(create(campaignBody().apply { put("kind", "SERVICE") })["kind"].asText()).isEqualTo("MARKETING")
    }

    @Test fun `공지 연결 뒤 감사 실패는 캠페인과 첫 알림 시각을 롤백한다`() {
        val notice = notices.save(com.knk.manyak.notice.entity.Notice(title = "공지", body = "본문",
            tag = com.knk.manyak.notice.entity.NoticeTag.UPDATE, status = com.knk.manyak.notice.entity.NoticeStatus.PUBLISHED,
            createdByUserId = admin.id))
        val placeholder = com.knk.manyak.admin.entity.AdminAuditLog(adminUserId = admin.id,
            category = com.knk.manyak.admin.entity.AdminAuditCategory.CHANGE,
            action = com.knk.manyak.admin.entity.AdminAuditAction.PUSH_CAMPAIGN_CREATED,
            targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.PUSH_CAMPAIGN, targetId = "fixture", requestId = "fixture")
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("audit failure"))
            .`when`(auditRepository).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java) ?: placeholder)
        call(HttpMethod.POST, "/campaigns", campaignBody().apply { put("noticeId", notice.publicId.toString()) }).expectStatus().is5xxServerError
        assertThat(notices.findById(notice.id).orElseThrow().notifiedAt).isNull()
        assertThat(campaigns.count()).isZero()
    }

    @Test fun `감사 저장 실패는 예약 취소 재실행 문구 변경과 복귀를 모두 롤백한다`() {
        val cancelId = create()["campaignId"].asText()
        val retryId = create()["campaignId"].asText()
        jdbc.update("UPDATE push_campaigns SET status='FAILED' WHERE public_id=?", UUID.fromString(retryId))
        templates.save(PushMessageTemplate(templateKey = "attendance_reminder", title = "기존", body = "본문", effectiveFrom = Instant.EPOCH))
        val placeholder = com.knk.manyak.admin.entity.AdminAuditLog(adminUserId = admin.id,
            category = com.knk.manyak.admin.entity.AdminAuditCategory.CHANGE,
            action = com.knk.manyak.admin.entity.AdminAuditAction.PUSH_CAMPAIGN_CREATED,
            targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.PUSH_CAMPAIGN, targetId = cancelId, requestId = "matcher")
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("injected audit failure"))
            .`when`(auditRepository).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java) ?: placeholder)
        call(HttpMethod.POST, "/campaigns", campaignBody()).expectStatus().is5xxServerError
        call(HttpMethod.POST, "/campaigns/$cancelId/cancel", mapOf("reason" to "취소")).expectStatus().is5xxServerError
        call(HttpMethod.POST, "/campaigns/$retryId/retry", mapOf("reason" to "재실행")).expectStatus().is5xxServerError
        call(HttpMethod.PUT, "/templates/attendance_reminder", templateBody()).expectStatus().is5xxServerError
        call(HttpMethod.DELETE, "/templates/attendance_reminder", mapOf("reason" to "복귀")).expectStatus().is5xxServerError
        assertThat(campaigns.findAll().map { it.status }).containsExactlyInAnyOrder(PushCampaignStatus.SCHEDULED, PushCampaignStatus.FAILED)
        assertThat(templates.findAll()).hasSize(1)
        assertThat(templates.findAll().single().effectiveUntil).isNull()
        assertThat(count("admin_audit_logs")).isEqualTo(2)
    }

    @Test fun `예약과 즉시 발송은 광고 예약이며 생성 감사를 남긴다`() {
        val before = Instant.now()
        val immediate = create()
        assertThat(immediate["kind"].asText()).isEqualTo("MARKETING")
        assertThat(immediate["status"].asText()).isEqualTo("SCHEDULED")
        assertThat(Instant.parse(immediate["scheduledAt"].asText())).isBetween(before, Instant.now())
        val future = Instant.now().plusSeconds(3600).toString()
        assertThat(create(campaignBody().apply { put("scheduledAt", future) })["scheduledAt"].asText()).isEqualTo(future)
        assertThat(count("admin_audit_logs")).isEqualTo(2)
        val audit = jdbc.queryForMap("SELECT * FROM admin_audit_logs ORDER BY id LIMIT 1")
        assertThat(audit["action"]).isEqualTo("PUSH_CAMPAIGN_CREATED")
        assertThat(audit["admin_user_id"]).isEqualTo(admin.id)
        assertThat(audit["target_id"]).isEqualTo(immediate["campaignId"].asText())
    }

    @Test fun `즉시 예약도 선점 전 취소되고 선점 뒤와 재취소는 거절한다`() {
        val id = create()["campaignId"].asText()
        call(HttpMethod.POST, "/campaigns/$id/cancel", mapOf("reason" to "취소"))
            .expectStatus().isOk.expectBody().jsonPath("$.status").isEqualTo("CANCELED")
        call(HttpMethod.POST, "/campaigns/$id/cancel", mapOf("reason" to "취소")) .expectStatus().isEqualTo(409)
        jdbc.update("UPDATE push_campaigns SET status = 'SENDING' WHERE public_id = ?", UUID.fromString(id))
        call(HttpMethod.POST, "/campaigns/$id/cancel", mapOf("reason" to "취소")) .expectStatus().isEqualTo(409)
        call(HttpMethod.POST, "/campaigns/${UUID.randomUUID()}/cancel", mapOf("reason" to "취소")) .expectStatus().isNotFound
        assertThat(count("admin_audit_logs")).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT action FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)).isEqualTo("PUSH_CAMPAIGN_CANCELED")
    }

    @Test fun `실패 재실행은 결과를 유지하고 예약 상태에서 재실행하지 않는다`() {
        val id = create()["campaignId"].asText()
        jdbc.update("UPDATE push_campaigns SET status='FAILED', failure_reason='NOTICE_NOT_PUBLISHED', target_count=7, sent_count=2, started_at=CURRENT_TIMESTAMP, finished_at=CURRENT_TIMESTAMP WHERE public_id=?", UUID.fromString(id))
        call(HttpMethod.GET, "/campaigns/$id").expectStatus().isOk.expectBody()
            .jsonPath("$.failureReason").isEqualTo("NOTICE_NOT_PUBLISHED")
        call(HttpMethod.GET, "/campaigns").expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].failureReason").isEqualTo("NOTICE_NOT_PUBLISHED")
        call(HttpMethod.POST, "/campaigns/$id/retry", mapOf("reason" to "장애 복구")) .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("SCHEDULED").jsonPath("$.targetCount").isEqualTo(7)
            .jsonPath("$.sentCount").isEqualTo(2).jsonPath("$.failureReason").isEmpty().jsonPath("$.startedAt").isNotEmpty().jsonPath("$.finishedAt").isNotEmpty()
        call(HttpMethod.POST, "/campaigns/$id/retry", mapOf("reason" to "재실행")) .expectStatus().isEqualTo(409)
        assertThat(jdbc.queryForObject("SELECT action FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)).isEqualTo("PUSH_CAMPAIGN_RETRIED")
    }

    @Test fun `취소와 선점이 경쟁하면 한쪽만 성공한다`() {
        val id = create()["campaignId"].asText()
        val internalId = campaigns.findAll().single().id
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val claim = pool.submit(Callable { gate.await(); campaigns.claim(internalId, Instant.now()) })
            val cancel = pool.submit(Callable { gate.await(); call(HttpMethod.POST, "/campaigns/$id/cancel", mapOf("reason" to "취소"))
                .expectBody(String::class.java).returnResult().status.value() })
            gate.countDown()
            val won = claim.get(30, TimeUnit.SECONDS)
            assertThat(cancel.get(30, TimeUnit.SECONDS)).isEqualTo(if (won == 1) 409 else 200)
        } finally { pool.shutdownNow() }
    }

    @Test fun `목록 페이지와 허용 정렬 및 상세는 공개 식별자를 사용한다`() {
        val ids = (1..3).map { create()["campaignId"].asText() }
        jdbc.update("UPDATE push_campaigns SET created_at = ?", java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"))
        call(HttpMethod.GET, "/campaigns?page=1&size=2&sort=createdAt&order=desc").expectStatus().isOk
            .expectBody().jsonPath("$.items.length()").isEqualTo(2).jsonPath("$.page").isEqualTo(1)
            .jsonPath("$.size").isEqualTo(2).jsonPath("$.totalElements").isEqualTo(3).jsonPath("$.totalPages").isEqualTo(2)
            .jsonPath("$.items[0].campaignId").isEqualTo(ids.sorted()[0])
        call(HttpMethod.GET, "/campaigns?page=2&size=2").expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(1)
        call(HttpMethod.GET, "/campaigns?status=FAILED").expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(0)
        call(HttpMethod.GET, "/campaigns/${ids[0]}").expectStatus().isOk.expectBody().jsonPath("$.campaignId").isEqualTo(ids[0])
        call(HttpMethod.GET, "/campaigns/${UUID.randomUUID()}").expectStatus().isNotFound
        call(HttpMethod.GET, "/campaigns/bad").expectStatus().isBadRequest
        for (query in listOf("page=0", "size=0", "size=101", "sort=id", "order=bad", "status=bad")) {
            call(HttpMethod.GET, "/campaigns?$query").expectStatus().isBadRequest
        }
    }

    @Test fun `미선점 무회원 발송 결과는 회원 영과 기존 기기 합계를 반환한다`() {
        val id = create()["campaignId"].asText()
        val internalId = campaigns.findAll().single().id
        for ((i, counts) in listOf(2 to 0, 1 to 1, 0 to 2).withIndex()) {
            jdbc.update("INSERT INTO push_delivery_results (message_id, type, kind, campaign_id, outcome, reason, delivered_devices, failed_devices, completed_at) " +
                "VALUES (?, 'PROMOTION', 'MARKETING', ?, ?, 'OK', ?, ?, CURRENT_TIMESTAMP)",
                "promotion:$id:$i", internalId, if (counts.first > 0) "DELIVERED" else "UNDELIVERED", counts.first, counts.second)
        }
        for (path in listOf("/campaigns/$id", "/campaigns")) {
            val prefix = if (path == "/campaigns") "$.items[0]" else "$"
            call(HttpMethod.GET, path).expectStatus().isOk.expectBody()
                .jsonPath("$prefix.deliveredMembers").isEqualTo(0)
                .jsonPath("$prefix.deliveredDevices").isEqualTo(3)
                .jsonPath("$prefix.failedDevices").isEqualTo(3)
        }
    }

    @Test fun `검색어는 제목 본문과 공개 UUID에 적용하고 종류 상태와 AND로 조합한다`() {
        val title = create(campaignBody().apply { put("title", "  Summer   Event  ") })["campaignId"].asText()
        val body = create(campaignBody().apply { put("body", "summer\tEVENT 소식") })["campaignId"].asText()
        val notice = create()["campaignId"].asText()
        jdbc.update("UPDATE push_campaigns SET kind='SERVICE', status='SENT' WHERE public_id=?", UUID.fromString(body))
        jdbc.update("UPDATE push_campaigns SET kind='SERVICE' WHERE public_id=?", UUID.fromString(notice))

        fun ids(query: String) = mapper.readTree(call(HttpMethod.GET, "/campaigns?$query")
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)["items"]
            .toList().map { it["campaignId"].asText() }
        assertThat(ids("q=  SUMMER   event  ")).containsExactlyInAnyOrder(title, body)
        assertThat(ids("q=summer event&kind=SERVICE&status=SENT")).containsExactly(body)
        assertThat(ids("q=summer event&kind=SERVICE&status=SCHEDULED")).isEmpty()
        assertThat(ids("kind=SERVICE")).containsExactlyInAnyOrder(body, notice)
        assertThat(ids("q=${title.uppercase()}")).containsExactly(title)
        assertThat(ids("q=${UUID.randomUUID()}")).isEmpty()
        // UUID 모양 검색어도 제목/본문의 부분 일치를 함께 적용한다.
        create(campaignBody().apply { put("title", "참조 $title") })
        assertThat(ids("q=$title")).hasSize(2)
    }

    @Test fun `검색어의 SQL 패턴 문자는 리터럴이며 잘못된 검색과 목록 쿼리를 거절한다`() {
        create(campaignBody().apply { put("title", "할인 100%_보장") })
        create()
        call(HttpMethod.GET, "/campaigns?q=%_").expectStatus().isOk.expectBody()
            .jsonPath("$.totalElements").isEqualTo(1)
        for (query in listOf("q=", "q=   ", "q=${"x".repeat(51)}", "kind=INFO", "sort=body",
            "sort=campaignId", "sort=startedAt", "sort=finishedAt", "order=ASC", "cursor=x", "limit=1")) {
            call(HttpMethod.GET, "/campaigns?$query").expectStatus().isBadRequest
        }
    }

    @Test fun `도달 세 정렬은 전체 집계를 기준으로 페이지를 나누고 동률은 UUID 오름차순이다`() {
        val ids = (1..4).map { create()["campaignId"].asText() }.sorted()
        // UUID 순서와 도달 순서를 다르게 만든다. 마지막 캠페인은 결과 행이 없어 0이다.
        val results = listOf(listOf(1 to 1), listOf(2 to 2, 1 to 1), listOf(3 to 3, 0 to 0))
        results.forEachIndexed { i, rows ->
            val campaign = campaigns.findByPublicId(UUID.fromString(ids[i]))!!
            jdbc.update("UPDATE push_campaigns SET started_at=? WHERE id=?", java.sql.Timestamp.from(Instant.EPOCH), campaign.id)
            rows.forEachIndexed { j, counts ->
                val recipient = users.save(User(nickname = "도달 정렬 $i $j"))
                jdbc.update("""INSERT INTO push_delivery_results
                    (message_id, type, kind, campaign_id, user_id, outcome, reason, delivered_devices, failed_devices, completed_at)
                    VALUES (?, 'PROMOTION', 'MARKETING', ?, ?, 'DELIVERED', 'OK', ?, ?, CURRENT_TIMESTAMP)""",
                    "$i:$j", campaign.id, recipient.id, counts.first, counts.second)
            }
        }
        for (sort in listOf("deliveredMembers", "deliveredDevices", "failedDevices")) {
            val expected = if (sort == "deliveredMembers") listOf(ids[1], ids[0], ids[2], ids[3])
                else listOf(ids[1], ids[2], ids[0], ids[3])
            for (order in listOf("asc", "desc")) {
                val ordered = if (order == "desc") expected else if (sort == "deliveredMembers")
                    listOf(ids[3], ids[0], ids[2], ids[1]) else listOf(ids[3], ids[0], ids[1], ids[2])
                val actual = (1..2).flatMap { page ->
                    val response = mapper.readTree(call(HttpMethod.GET, "/campaigns?sort=$sort&order=$order&page=$page&size=2")
                        .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
                    assertThat(response["totalElements"].asInt()).isEqualTo(4)
                    assertThat(response["totalPages"].asInt()).isEqualTo(2)
                    response["items"].toList().map { it["campaignId"].asText() }
                }
                assertThat(actual).containsExactlyElementsOf(ordered)
            }
        }
        call(HttpMethod.GET, "/campaigns?sort=deliveredDevices&q=${ids[1]}&kind=MARKETING&status=SCHEDULED")
            .expectStatus().isOk.expectBody().jsonPath("$.totalElements").isEqualTo(1)
            .jsonPath("$.items[0].deliveredDevices").isEqualTo(3)
        call(HttpMethod.GET, "/campaigns?sort=failedDevices&page=3&size=2").expectStatus().isOk
            .expectBody().jsonPath("$.items.length()").isEqualTo(0).jsonPath("$.totalElements").isEqualTo(4)
    }

    @Test fun `대상과 발송 수 정렬은 방향과 관계없이 null을 마지막에 둔다`() {
        val empty = create()["campaignId"].asText()
        val counted = create()["campaignId"].asText()
        jdbc.update("UPDATE push_campaigns SET target_count=1, sent_count=0 WHERE public_id=?", UUID.fromString(counted))
        for (sort in listOf("targetCount", "sentCount")) for (order in listOf("asc", "desc")) {
            call(HttpMethod.GET, "/campaigns?sort=$sort&order=$order").expectStatus().isOk.expectBody()
                .jsonPath("$.items[0].campaignId").isEqualTo(counted)
                .jsonPath("$.items[1].campaignId").isEqualTo(empty)
        }
    }

    @Test fun `대상 수는 활성 토큰 보유와 종류별 동의 및 야간 동의를 구분한다`() {
        repeat(5) { i ->
            val user = users.save(User(nickname = "회원$i", marketingPushAgreedAt = if (i < 3) Instant.EPOCH else null,
                marketingPushNightAgreedAt = if (i == 0) Instant.EPOCH else null))
            if (i != 2) jdbc.update("INSERT INTO device_push_tokens(user_id, token, platform, created_at, updated_at) VALUES (?, ?, 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", user.id, "token-$i")
        }
        call(HttpMethod.GET, "/campaigns/target-count?kind=MARKETING").expectStatus().isOk.expectBody()
            .jsonPath("$.targetCount").isEqualTo(2).jsonPath("$.nightEligibleCount").isEqualTo(1)
        call(HttpMethod.GET, "/campaigns/target-count?kind=SERVICE").expectStatus().isOk.expectBody()
            .jsonPath("$.targetCount").isEqualTo(4).jsonPath("$.nightEligibleCount").isEmpty()
        call(HttpMethod.GET, "/campaigns/target-count").expectStatus().isBadRequest
        call(HttpMethod.GET, "/campaigns/target-count?kind=INFO").expectStatus().isBadRequest
    }

    @Test fun `예약 입력과 인증 오류를 저장 전에 거절한다`() {
        for ((field, value) in listOf("scheduledAt" to Instant.now().minusSeconds(1).toString(), "title" to "x".repeat(101),
            "body" to "x".repeat(301), "reason" to " ")) {
            call(HttpMethod.POST, "/campaigns", campaignBody().apply { put(field, value) }).expectStatus().isBadRequest
        }
        call(HttpMethod.POST, "/campaigns", campaignBody().apply { remove("reason") }).expectStatus().isBadRequest
        call(HttpMethod.GET, "/campaigns", auth = null).expectStatus().isUnauthorized
        call(HttpMethod.GET, "/campaigns", auth = jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
        jdbc.update("DELETE FROM admin_accounts WHERE user_id=?", admin.id)
        call(HttpMethod.GET, "/campaigns").expectStatus().isForbidden
        assertThat(count("push_campaigns")).isZero()
    }

    @Test fun `관리자 OpenAPI는 이프 만료 알림 결과 필터를 문서화한다`() {
        val document = client.get().uri("/v3/api-docs").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val root = mapper.readTree(document)
        val type = root["paths"]["/api/v1/admin/push/delivery-stats"]["get"]["parameters"]
            .first { it["name"].asText() == "type" }["schema"]["enum"]
        assertThat(type.toList().map { it.asText() }).contains("CREDIT_EXPIRY_REMINDER")
        val export = java.nio.file.Path.of("build/reports/admin-openapi.json")
        java.nio.file.Files.createDirectories(export.parent)
        java.nio.file.Files.writeString(export, document)
    }

    @Test fun `이프 만료 템플릿의 조회 변경 복귀는 출석 문구와 독립적이다`() {
        val path = "/templates/credit_expiry_reminder"
        call(HttpMethod.GET, path).expectStatus().isOk.expectBody()
            .jsonPath("$.effective.title").isEqualTo("이프 만료 예정 안내")
            .jsonPath("$.effective.body").isEqualTo("곧 만료되는 이프가 있어요")
        call(HttpMethod.PUT, path, mapOf("title" to "만료 안내", "body" to "확인해 주세요", "reason" to "문구 변경"))
            .expectStatus().isOk.expectBody().jsonPath("$.effective.title").isEqualTo("만료 안내")
            .jsonPath("$.overrides[0].active").isEqualTo(true)
        call(HttpMethod.GET, path).expectStatus().isOk.expectBody().jsonPath("$.effective.isDefault").isEqualTo(false)
        text.refresh()
        assertThat(text.templateOf(PushTemplateKey.CREDIT_EXPIRY_REMINDER).title).isEqualTo("만료 안내")
        assertThat(templateGet()["effective"]["isDefault"].asBoolean()).isTrue()
        call(HttpMethod.DELETE, path, mapOf("reason" to "기본 복귀")).expectStatus().isOk.expectBody()
            .jsonPath("$.effective.isDefault").isEqualTo(true)
        call(HttpMethod.GET, path).expectStatus().isOk.expectBody().jsonPath("$.effective.title").isEqualTo("이프 만료 예정 안내")
        call(HttpMethod.GET, "/templates/missing").expectStatus().isNotFound
    }

    @Test fun `문구는 행을 추가하고 즉시 조회와 발송 스냅샷을 구분한다`() {
        assertThat(templateGet()["effective"]["isDefault"].asBoolean()).isTrue()
        call(HttpMethod.PUT, "/templates/attendance_reminder", templateBody()).expectStatus().isOk
            .expectBody().jsonPath("$.effective.title").isEqualTo("새 출석 문구").jsonPath("$.effective.isDefault").isEqualTo(false)
        assertThat(text.templateOf(PushTemplateKey.ATTENDANCE_REMINDER).title).isNotEqualTo("새 출석 문구")
        text.refresh()
        assertThat(text.templateOf(PushTemplateKey.ATTENDANCE_REMINDER).title).isEqualTo("새 출석 문구")
        call(HttpMethod.PUT, "/templates/attendance_reminder", templateBody()).expectStatus().isOk
        assertThat(templates.count()).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT action FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)).isEqualTo("PUSH_TEMPLATE_UPSERTED")
    }

    @Test fun `같은 시작이면 큰 id 문구가 관리자 조회와 발송 스냅샷에 적용된다`() {
        val from = Instant.now().minusSeconds(1)
        templates.save(PushMessageTemplate(templateKey = "attendance_reminder", title = "이전", body = "본문", effectiveFrom = from))
        templates.save(PushMessageTemplate(templateKey = "attendance_reminder", title = "나중", body = "본문", effectiveFrom = from))
        assertThat(templateGet()["effective"]["title"].asText()).isEqualTo("나중")
        text.refresh()
        assertThat(text.templateOf(PushTemplateKey.ATTENDANCE_REMINDER).title).isEqualTo("나중")
    }

    @Test fun `과거 시작과 역전 기간은 거절하고 예약 문구를 허용한다`() {
        for (data in listOf(templateBody().apply { put("effectiveFrom", Instant.now().minusSeconds(1).toString()) },
            templateBody().apply { put("effectiveFrom", Instant.now().plusSeconds(100).toString()); put("effectiveUntil", Instant.now().toString()) })) {
            call(HttpMethod.PUT, "/templates/attendance_reminder", data).expectStatus().isBadRequest
        }
        call(HttpMethod.PUT, "/templates/missing", templateBody()).expectStatus().isNotFound
        call(HttpMethod.PUT, "/templates/attendance_reminder", templateBody().apply { put("effectiveFrom", Instant.now().plusSeconds(3600).toString()) })
            .expectStatus().isOk.expectBody().jsonPath("$.effective.isDefault").isEqualTo(true)
    }

    @Test fun `기본 복귀는 활성과 미래 행을 종료하고 행은 삭제하지 않는다`() {
        templates.save(PushMessageTemplate(templateKey = "attendance_reminder", title = "현재", body = "본문", effectiveFrom = Instant.now().minusSeconds(3600)))
        templates.save(PushMessageTemplate(templateKey = "attendance_reminder", title = "미래", body = "본문", effectiveFrom = Instant.now().plusSeconds(3600)))
        call(HttpMethod.DELETE, "/templates/attendance_reminder", mapOf("reason" to "기본 복귀")).expectStatus().isOk
            .expectBody().jsonPath("$.effective.isDefault").isEqualTo(true)
        val rows = templates.findAll().sortedBy { it.id }
        assertThat(rows).hasSize(2)
        assertThat(rows[0].effectiveUntil).isBefore(Instant.now())
        assertThat(rows[1].effectiveUntil).isEqualTo(rows[1].effectiveFrom)
        call(HttpMethod.DELETE, "/templates/attendance_reminder", mapOf("reason" to "반복 복귀")).expectStatus().isOk
        assertThat(jdbc.queryForObject("SELECT action FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)).isEqualTo("PUSH_TEMPLATE_RESET")
    }
}
