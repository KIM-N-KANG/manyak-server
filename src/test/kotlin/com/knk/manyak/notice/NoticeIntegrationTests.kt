package com.knk.manyak.notice

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.repository.PushCampaignRepository
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoticeIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var campaigns: PushCampaignRepository
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var audits: com.knk.manyak.admin.repository.AdminAuditLogRepository
    private val mapper = ObjectMapper()
    private lateinit var admin: User
    private lateinit var token: String
    private val root = "/api/v1/admin/notices"
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.save(User(nickname = "공지 관리자", servicePushEnabled = false))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun call(method: HttpMethod, path: String = "", body: Any? = null, auth: String? = token) =
        client.method(method).uri(root + path).contentType(MediaType.APPLICATION_JSON)
            .apply { auth?.let { header("Authorization", "Bearer $it") }; body?.let { body(it) } }.exchange()
    private fun json(response: RestTestClient.ResponseSpec): JsonNode = mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun create(title: String = "공지 제목", body: String = "공지 본문", tag: String = "SERVICE") = json(call(HttpMethod.POST, body = mapOf("title" to title, "body" to body, "tag" to tag, "reason" to "작성" )).expectStatus().isCreated)
    private fun publish(id: String, notify: Boolean = false, kind: String = "SERVICE") = json(call(HttpMethod.POST, "/$id/publish", mapOf("notify" to notify, "notificationKind" to kind, "reason" to "게시")).expectStatus().isOk)
    private fun unpublish(id: String) = json(call(HttpMethod.POST, "/$id/unpublish", mapOf("reason" to "해제")).expectStatus().isOk)
    private fun public(path: String = "", auth: String? = null) = client.get().uri("/api/v1/notices$path").apply { auth?.let { header("Authorization", "Bearer $it") } }.exchange()
    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)!!

    @Test fun `작성 수정 게시 해제 삭제는 감사와 함께 처리하고 공개 범위를 지킨다`() {
        val draft = create(); val id = draft["noticeId"].asText()
        assertThat(draft["status"].asText()).isEqualTo("DRAFT")
        assertThat(draft["createdBy"]["memberId"].asText()).isEqualTo(admin.publicId.toString())
        public("/$id").expectStatus().isNotFound
        val changed = json(call(HttpMethod.PATCH, "/$id", mapOf("title" to "고친 제목", "reason" to "수정")).expectStatus().isOk)
        assertThat(changed["body"].asText()).isEqualTo("공지 본문")
        assertThat(publish(id)["notified"].asBoolean()).isTrue()
        assertThat(json(public("/$id").expectStatus().isOk)["title"].asText()).isEqualTo("고친 제목")
        call(HttpMethod.PATCH, "/$id", mapOf("body" to "공개 수정", "reason" to "수정")).expectStatus().isOk
        assertThat(json(public("/$id").expectStatus().isOk)["body"].asText()).isEqualTo("공개 수정")
        val time = unpublish(id)["publishedAt"].asText()
        assertThat(time).isNotBlank()
        public("/$id").expectStatus().isNotFound
        call(HttpMethod.DELETE, "/$id", mapOf("reason" to "삭제")).expectStatus().isEqualTo(409)
        val disposable = create()["noticeId"].asText()
        call(HttpMethod.DELETE, "/$disposable", mapOf("reason" to "삭제")).expectStatus().isNoContent
        assertThat(count("notices")).isEqualTo(1)
        assertThat(count("push_campaigns")).isEqualTo(1)
        val rows = jdbc.queryForList("SELECT * FROM admin_audit_logs ORDER BY id")
        assertThat(rows.map { it["action"] }).containsExactly("NOTICE_CREATED", "NOTICE_UPDATED", "NOTICE_PUBLISHED", "NOTICE_UPDATED", "NOTICE_UNPUBLISHED", "NOTICE_CREATED", "NOTICE_DELETED")
        for (row in rows) {
            for (name in listOf("before_state", "after_state")) row[name]?.let {
                val node = mapper.readTree(if (it is ByteArray) String(it, Charsets.UTF_8) else it.toString())
                val fields = if (row["action"] in setOf("NOTICE_PUBLISHED", "NOTICE_UNPUBLISHED"))
                    setOf("status", "publishedAt", "notifiedAt") else setOf("title", "tag", "status")
                assertThat(node.properties().map { it.key }.toSet()).isEqualTo(fields)
                assertThat(node.has("body")).isFalse()
            }
        }
    }
    @Test fun `게시와 게시 해제 감사는 상태 및 시각의 전후 값을 기록한다`() {
        fun state(row: JsonNode) = mapper.createObjectNode().apply {
            put("status", row["status"].asText())
            set("publishedAt", row["publishedAt"])
            set("notifiedAt", row["notifiedAt"])
        }
        fun audit(id: String, action: String, column: String): JsonNode {
            val value = jdbc.queryForMap("SELECT before_state,after_state FROM admin_audit_logs WHERE target_id=? AND action=?", id, action)[column]!!
            return mapper.readTree(if (value is ByteArray) String(value, Charsets.UTF_8) else value.toString())
        }
        for (notify in listOf(false, true)) {
            val draft = create("감사 시각 $notify")
            val id = draft["noticeId"].asText()
            val published = publish(id, notify)
            assertThat(audit(id, "NOTICE_PUBLISHED", "before_state")).isEqualTo(state(draft))
            assertThat(audit(id, "NOTICE_PUBLISHED", "after_state")).isEqualTo(state(published))
            assertThat(published["publishedAt"].isString).isTrue()
            assertThat(published["notifiedAt"].isNull).isFalse()
            assertThat(java.time.Instant.parse(published["notifiedAt"].asText()))
                .isEqualTo(java.time.Instant.parse(published["publishedAt"].asText()))
            val unpublished = unpublish(id)
            assertThat(audit(id, "NOTICE_UNPUBLISHED", "before_state")).isEqualTo(state(published))
            assertThat(audit(id, "NOTICE_UNPUBLISHED", "after_state")).isEqualTo(state(unpublished))
            assertThat(unpublished["publishedAt"]).isEqualTo(published["publishedAt"])
            assertThat(unpublished["notifiedAt"]).isEqualTo(published["notifiedAt"])
        }
    }
    @Test fun `UUID 검색은 공개 식별자와 제목 부분 일치를 OR로 결합하고 상태 필터를 유지한다`() {
        val id = create("식별자 검색 대상")["noticeId"].asText()
        val titleId = create("참조 ${id.uppercase()} 공지")["noticeId"].asText()
        create("무관한 공지")
        val all = json(call(HttpMethod.GET, "?q=${id.uppercase()}").expectStatus().isOk)
        assertThat(all["totalElements"].asLong()).isEqualTo(2)
        assertThat(all["items"].toList().map { it["noticeId"].asText() }).containsExactlyInAnyOrder(id, titleId)
        publish(id)
        val drafts = json(call(HttpMethod.GET, "?q=$id&status=DRAFT").expectStatus().isOk)
        assertThat(drafts["totalElements"].asLong()).isEqualTo(1)
        assertThat(drafts["items"][0]["noticeId"].asText()).isEqualTo(titleId)
    }
    @Test fun `공개 목록 limit 0과 음수는 1로 큰 값은 50으로 보정하며 다음 페이지를 유지한다`() {
        repeat(51) { index ->
            jdbc.update("""INSERT INTO notices(public_id,title,body,tag,status,published_at,created_by_user_id,created_at,updated_at)
                VALUES (?,?,'본문','SERVICE','PUBLISHED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)""",
                UUID.randomUUID(), "공개 보정 $index", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(index.toLong())), admin.id)
        }
        create("목록에서 제외할 초안")
        for (limit in listOf(0, -1, 999)) {
            val page = json(public("?limit=$limit").expectStatus().isOk)
            assertThat(page["items"].size()).isEqualTo(if (limit == 999) 50 else 1)
            assertThat(page["nextCursor"].isString).isTrue()
            if (limit == 999) {
                val next = json(public("?limit=999&cursor=${page["nextCursor"].asText()}").expectStatus().isOk)
                assertThat(next["items"].size()).isEqualTo(1)
                assertThat(next["nextCursor"].isNull).isTrue()
            }
        }
    }
    @Test fun `알림 게시의 캠페인은 종류 공지 식별자 본문 300자를 갖고 재게시해도 한 건이다`() {
        for (tag in listOf("SERVICE", "UPDATE", "MAINTENANCE", "EVENT", "PROMOTION")) {
            val kind = if (tag in listOf("EVENT", "PROMOTION")) "MARKETING" else "SERVICE"
            val id = create("공지 $tag", "시작\r\n" + "가".repeat(400), tag)["noticeId"].asText()
            val result = publish(id, true, kind)
            assertThat(result["notified"].asBoolean()).isTrue()
            assertThat(result["notifiedAt"].isNull).isFalse()
            val campaign = campaigns.findAll().last()
            assertThat(campaign.kind.name).isEqualTo(kind)
            assertThat(campaign.noticeTag!!.name).isEqualTo(tag)
            assertThat(campaign.body).hasSize(300).doesNotContain("\n", "\r")
            assertThat(jdbc.queryForObject("SELECT notice_id FROM push_campaigns WHERE id=?", Long::class.java, campaign.id))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM notices WHERE public_id=?", Long::class.java, UUID.fromString(id)))
            val detail = json(client.get().uri("/api/v1/admin/push/campaigns/${campaign.publicId}").header("Authorization", "Bearer $token").exchange().expectStatus().isOk)
            assertThat(detail["noticeId"].asText()).isEqualTo(id)
            call(HttpMethod.DELETE, "/$id", mapOf("reason" to "삭제")).expectStatus().isEqualTo(409)
            unpublish(id)
            call(HttpMethod.DELETE, "/$id", mapOf("reason" to "삭제")).expectStatus().isEqualTo(409)
            assertThat(publish(id, true, kind)["notified"].asBoolean()).isFalse()
        }
        assertThat(count("push_campaigns")).isEqualTo(5)
    }
    @Test fun `공개 목록은 게시만 커서로 조회하며 토큰과 작성자와 본문을 싣지 않는다`() {
        create("숨김")
        repeat(3) { publish(create("공개 $it")["noticeId"].asText()) }
        for (auth in listOf(null, "expired.invalid.token", token, jwt.issueAccessToken(admin.publicId))) {
            val first = json(public("?limit=2", auth).expectStatus().isOk)
            assertThat(first["items"].size()).isEqualTo(2)
            assertThat(first["items"][0].has("body")).isFalse()
            assertThat(first["items"][0].has("createdBy")).isFalse()
            val next = first["nextCursor"].asText()
            assertThat(next).isNotBlank()
            val second = json(public("?limit=2&cursor=$next", auth).expectStatus().isOk)
            assertThat(second["items"].size()).isEqualTo(1)
            assertThat(second["nextCursor"].isNull).isTrue()
            public("/${first["items"][0]["noticeId"].asText()}", auth).expectStatus().isOk
        }
        public("/not-a-uuid").expectStatus().isNotFound
        public("/${UUID.randomUUID()}").expectStatus().isNotFound
        public("?cursor=bad").expectStatus().isBadRequest
    }
    @Test fun `관리자 목록은 검색 상태 정렬 NULL 후순위와 번호 페이지를 지원한다`() {
        val one = create("동일 검색")["noticeId"].asText(); val two = create("동일 검색")["noticeId"].asText()
        publish(one, true)
        for (direction in listOf("asc", "desc")) {
            val body = json(call(HttpMethod.GET, "?q=동일&sort=notifiedAt&order=$direction&size=1").expectStatus().isOk)
            assertThat(body["totalElements"].asLong()).isEqualTo(2)
            assertThat(body["totalPages"].asLong()).isEqualTo(2)
            assertThat(body["items"][0]["noticeId"].asText()).isEqualTo(one)
            assertThat(body["items"][0].has("body")).isFalse()
        }
        assertThat(json(call(HttpMethod.GET, "?q=$two&status=DRAFT").expectStatus().isOk)["items"].size()).isEqualTo(1)
        assertThat(json(call(HttpMethod.GET, "?page=999").expectStatus().isOk)["items"].size()).isZero()
    }
    @Test fun `입력과 상태 오류는 계약의 400 404 409로 응답한다`() {
        for (body in listOf(mapOf("title" to "", "body" to "본문", "reason" to "사유"),
            mapOf("title" to "가".repeat(101), "body" to "본문", "reason" to "사유"),
            mapOf("title" to "제목", "body" to "가".repeat(5001), "reason" to "사유"),
            mapOf("title" to "제목", "body" to "  ", "reason" to "사유"), mapOf("title" to "제목", "body" to "본문"))) {
            call(HttpMethod.POST, body = body + ("tag" to "SERVICE")).expectStatus().isBadRequest
        }
        val id = create()["noticeId"].asText()
        for (body in listOf(mapOf("reason" to "수정"), mapOf("title" to null, "body" to null, "reason" to "수정"))) call(HttpMethod.PATCH, "/$id", body).expectStatus().isBadRequest
        call(HttpMethod.POST, "/$id/publish", emptyMap<String, String>()).expectStatus().isBadRequest
        call(HttpMethod.POST, "/$id/unpublish", mapOf("reason" to "해제")).expectStatus().isEqualTo(409)
        publish(id)
        call(HttpMethod.POST, "/$id/publish", mapOf("notify" to false, "reason" to "게시")).expectStatus().isEqualTo(409)
        for (query in listOf("status=ARCHIVED", "page=0", "size=101", "sort=body", "order=ASC", "cursor=x", "limit=3", "q=", "q=${"x".repeat(51)}")) call(HttpMethod.GET, "?$query").expectStatus().isBadRequest
        call(HttpMethod.PATCH, "/$id", mapOf("body" to "　", "reason" to "수정")).expectStatus().isBadRequest
        call(HttpMethod.GET, auth = null).expectStatus().isUnauthorized
        call(HttpMethod.GET, auth = jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
        call(HttpMethod.GET, "/bad").expectStatus().isBadRequest
        call(HttpMethod.GET, "/${UUID.randomUUID()}").expectStatus().isNotFound
    }
    @Test fun `동시 게시 요청은 한 건만 게시하고 캠페인도 한 건이다`() {
        val id = create()["noticeId"].asText()
        val ready = CountDownLatch(2); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map { pool.submit(Callable {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                call(HttpMethod.POST, "/$id/publish", mapOf("notify" to true, "notificationKind" to "SERVICE", "reason" to "동시 게시"))
                    .returnResult(String::class.java).status.value()
            }) }
            check(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            assertThat(tasks.map { it.get(10, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 409)
            assertThat(count("push_campaigns")).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }
    @Test fun `감사 저장 실패는 모든 공지 변경과 게시 캠페인을 롤백한다`() {
        val draft = create()["noticeId"].asText()
        val published = create()["noticeId"].asText(); publish(published)
        val beforeCount = count("admin_audit_logs")
        val placeholder = com.knk.manyak.admin.entity.AdminAuditLog(adminUserId = admin.id,
            category = com.knk.manyak.admin.entity.AdminAuditCategory.CHANGE,
            action = com.knk.manyak.admin.entity.AdminAuditAction.NOTICE_CREATED,
            targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.NOTICE, targetId = draft, requestId = "fixture")
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("audit failure"))
            .`when`(audits).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java) ?: placeholder)
        call(HttpMethod.POST, body = mapOf("title" to "실패", "body" to "실패", "tag" to "SERVICE", "reason" to "작성")).expectStatus().is5xxServerError
        call(HttpMethod.PATCH, "/$draft", mapOf("title" to "실패", "reason" to "수정")).expectStatus().is5xxServerError
        call(HttpMethod.POST, "/$draft/publish", mapOf("notify" to true, "notificationKind" to "SERVICE", "reason" to "게시")).expectStatus().is5xxServerError
        call(HttpMethod.POST, "/$published/unpublish", mapOf("reason" to "해제")).expectStatus().is5xxServerError
        call(HttpMethod.DELETE, "/$draft", mapOf("reason" to "삭제")).expectStatus().is5xxServerError
        assertThat(count("notices")).isEqualTo(2)
        assertThat(count("push_campaigns")).isEqualTo(1)
        assertThat(count("admin_audit_logs")).isEqualTo(beforeCount)
        val row = json(call(HttpMethod.GET, "/$draft").expectStatus().isOk)
        assertThat(row["title"].asText()).isEqualTo("공지 제목")
        assertThat(row["status"].asText()).isEqualTo("DRAFT")
        assertThat(row["notifiedAt"].isNull).isTrue()
        assertThat(json(call(HttpMethod.GET, "/$published").expectStatus().isOk)["status"].asText()).isEqualTo("PUBLISHED")
    }

    @Test fun `게시 해제와 재게시가 겹쳐도 상태와 알림 1회성이 섞이지 않는다`() {
        val id = create()["noticeId"].asText(); publish(id, true)
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val off = pool.submit(Callable { check(start.await(5, TimeUnit.SECONDS));
                call(HttpMethod.POST, "/$id/unpublish", mapOf("reason" to "해제")).returnResult(String::class.java).status.value() })
            val on = pool.submit(Callable { check(start.await(5, TimeUnit.SECONDS));
                call(HttpMethod.POST, "/$id/publish", mapOf("notify" to true, "notificationKind" to "SERVICE", "reason" to "게시")).returnResult(String::class.java).status.value() })
            start.countDown()
            assertThat(off.get(10, TimeUnit.SECONDS)).isEqualTo(200)
            val status = on.get(10, TimeUnit.SECONDS)
            assertThat(status).isIn(200, 409)
            val row = json(call(HttpMethod.GET, "/$id").expectStatus().isOk)
            assertThat(row["status"].asText()).isEqualTo(if (status == 200) "PUBLISHED" else "DRAFT")
            assertThat(row["publishedAt"].isNull).isFalse()
            assertThat(count("push_campaigns")).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `캠페인 저장 실패는 게시와 알림 시각 및 감사 기록을 롤백한다`() {
        val id = create()["noticeId"].asText()
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("campaign failure"))
            .`when`(campaigns).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.push.entity.PushCampaign::class.java)
                ?: com.knk.manyak.push.entity.PushCampaign(title = "", body = "", scheduledAt = java.time.Instant.EPOCH))
        call(HttpMethod.POST, "/$id/publish", mapOf("notify" to true, "notificationKind" to "SERVICE", "reason" to "게시")).expectStatus().is5xxServerError
        val notice = json(call(HttpMethod.GET, "/$id").expectStatus().isOk)
        assertThat(notice["status"].asText()).isEqualTo("DRAFT")
        assertThat(notice["notifiedAt"].isNull).isTrue()
        assertThat(count("admin_audit_logs")).isEqualTo(1)
    }
    @Test fun `공지 OpenAPI는 공개와 관리자 인증을 구분하고 평면 응답을 문서화한다`() {
        val api = json(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk)
        assertThat(api["paths"]["/api/v1/notices"]["get"]["security"]).isNull()
        assertThat(api["paths"]["/api/v1/admin/notices"]["get"]["security"].toString()).contains("bearerAuth")
        assertThat(api["components"]["schemas"]["AdminNoticeResponse"]["properties"].has("noticeId")).isTrue()
    }
    @Test fun `알림 없이 게시했던 공지도 첫 알림 요청은 재게시 때 한 번 가능하다`() {
        val id = create()["noticeId"].asText()
        jdbc.update("UPDATE notices SET published_at=CURRENT_TIMESTAMP WHERE public_id=?", UUID.fromString(id))
        assertThat(publish(id, true)["notified"].asBoolean()).isTrue()
        assertThat(count("push_campaigns")).isEqualTo(1)
    }

    @Test fun `태그는 생성 필수 수정 선택이고 잘못된 값은 400이다`() {
        for (tag in listOf(null, "NOTICE")) {
            val body = mutableMapOf<String, Any>("title" to "제목", "body" to "본문", "reason" to "작성")
            tag?.let { body["tag"] = it }
            call(HttpMethod.POST, body = body).expectStatus().isBadRequest
        }
        val draft = create(tag = "UPDATE")
        assertThat(draft["tag"].asText()).isEqualTo("UPDATE")
        val id = draft["noticeId"].asText()
        call(HttpMethod.PATCH, "/$id", mapOf("tag" to "NOTICE", "reason" to "수정")).expectStatus().isBadRequest
        call(HttpMethod.PATCH, "/$id", mapOf("tag" to "EVENT")).expectStatus().isBadRequest
        val changed = json(call(HttpMethod.PATCH, "/$id", mapOf("tag" to "EVENT", "reason" to "수정")).expectStatus().isOk)
        assertThat(changed["title"]).isEqualTo(draft["title"])
        assertThat(changed["body"]).isEqualTo(draft["body"])
        assertThat(changed["tag"].asText()).isEqualTo("EVENT")
        val all = json(call(HttpMethod.PATCH, "/$id", mapOf("title" to "셋", "body" to "본문 수정", "tag" to "UPDATE", "reason" to "수정")).expectStatus().isOk)
        assertThat(all["tag"].asText()).isEqualTo("UPDATE")
        assertThat(all["title"].asText()).isEqualTo("셋")
        assertThat(all["body"].asText()).isEqualTo("본문 수정")
    }

    @Test fun `게시 본문은 reason만 받고 예전 필드는 값과 타입에 관계없이 무시한다`() {
        for (legacy in listOf(emptyMap(), mapOf("notify" to false, "notificationKind" to "MARKETING"),
            mapOf("notify" to "invalid", "notificationKind" to listOf("OTHER")))) {
            val id = create()["noticeId"].asText()
            val result = json(call(HttpMethod.POST, "/$id/publish", legacy + ("reason" to "게시")).expectStatus().isOk)
            assertThat(result["notified"].asBoolean()).isTrue()
            assertThat(campaigns.findAll().last().kind.name).isEqualTo("SERVICE")
        }
        assertThat(count("push_campaigns")).isEqualTo(3)
    }

    @Test fun `공개 태그 필터와 커서는 일치하는 게시만 반환하며 원문 제목을 보존한다`() {
        for (tag in listOf("SERVICE", "SERVICE", "MAINTENANCE", "EVENT", "MAINTENANCE")) {
            publish(create("원문 $tag", tag = tag)["noticeId"].asText())
        }
        val page = json(public("?tag=MAINTENANCE&limit=1").expectStatus().isOk)
        assertThat(page["items"].size()).isEqualTo(1)
        assertThat(page["items"][0]["tag"].asText()).isEqualTo("MAINTENANCE")
        val next = json(public("?tag=MAINTENANCE&limit=1&cursor=${page["nextCursor"].asText()}").expectStatus().isOk)
        assertThat(next["items"].size()).isEqualTo(1)
        assertThat(next["items"][0]["tag"].asText()).isEqualTo("MAINTENANCE")
        assertThat(next["nextCursor"].isNull).isTrue()
        public("?tag=NOTICE").expectStatus().isBadRequest
        val event = json(public("?tag=EVENT").expectStatus().isOk)["items"][0]
        assertThat(event["title"].asText()).isEqualTo("원문 EVENT")
        val detail = json(public("/${event["noticeId"].asText()}").expectStatus().isOk)
        assertThat(detail["title"]).isEqualTo(event["title"])
        assertThat(detail["tag"].asText()).isEqualTo("EVENT")
        assertThat(campaigns.findAll().single { it.noticeTag?.name == "EVENT" }.title).isEqualTo("원문 EVENT")
    }

    @Test fun `게시 후 태그 수정과 재게시는 최초 캠페인 사본을 바꾸지 않는다`() {
        val id = create()["noticeId"].asText()
        publish(id)
        call(HttpMethod.PATCH, "/$id", mapOf("tag" to "EVENT", "reason" to "수정")).expectStatus().isOk
        unpublish(id)
        assertThat(publish(id)["notified"].asBoolean()).isFalse()
        assertThat(campaigns.findAll()).hasSize(1)
        assertThat(campaigns.findAll().single().kind.name).isEqualTo("SERVICE")
        assertThat(campaigns.findAll().single().noticeTag!!.name).isEqualTo("SERVICE")
    }

}
