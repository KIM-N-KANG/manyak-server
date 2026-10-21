package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

@org.springframework.context.annotation.Import(AdminReadClockConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.admin.dashboard.excluded-user-ids="])
class AdminReadApiIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var socials: SocialAccountRepository
    @Autowired private lateinit var tokens: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private lateinit var admin: User
    private lateinit var token: String
    private val mapper = ObjectMapper()

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = tokens.issueAdminToken(admin.publicId)
    }
    private fun get(path: String) = client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer $token").exchange()
    private fun json(response: RestTestClient.ResponseSpec): JsonNode =
        mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun member(name: String, status: UserStatus = UserStatus.ACTIVE, id: UUID = UUID.randomUUID(),
                       created: Instant = Instant.parse("2026-01-01T00:00:00Z")) =
        users.save(User(nickname = name, status = status, publicId = id, createdAt = created))

    @Autowired private lateinit var audits: com.knk.manyak.admin.service.AdminAuditService
    @Autowired private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    @Autowired private lateinit var orders: com.knk.manyak.credit.repository.CreditOrderRepository
    @Autowired private lateinit var lots: com.knk.manyak.credit.repository.CreditLotRepository
    @Autowired private lateinit var storyRepository: com.knk.manyak.story.repository.StoryRepository
    @Autowired private lateinit var chatRepository: com.knk.manyak.chat.repository.StoryChatRepository
    @Autowired private lateinit var versions: com.knk.manyak.chat.repository.StoryMessageVersionRepository
    @Autowired private lateinit var messageRepository: com.knk.manyak.chat.repository.StoryMessageRepository
    @Autowired private lateinit var dashboardRepository: com.knk.manyak.admin.repository.AdminDashboardRepository
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var auditRepository: com.knk.manyak.admin.repository.AdminAuditLogRepository
    private val now = Instant.parse("2026-10-20T15:30:00Z")
    private val midnight = Instant.parse("2026-10-20T15:00:00Z")

    private fun order(user: User = admin, status: com.knk.manyak.credit.entity.CreditOrderStatus = com.knk.manyak.credit.entity.CreditOrderStatus.COMPLETED,
                      completed: Instant? = now, price: Long = 4800, credit: Long = 5000, id: UUID = UUID.randomUUID(), refund: Instant? = null) =
        orders.save(com.knk.manyak.credit.entity.CreditOrder(userId = user.id, productId = "if_5000",
            provider = com.knk.manyak.credit.entity.CreditOrderProvider.GROBLE, status = status, priceKrw = price, creditAmount = credit,
            completedAt = completed, publicId = id, refundedAt = refund))

    @Test fun `회원 가입 시각 정렬은 양방향을 지원하고 탈퇴 연동 이메일은 검색하지 않는다`() {
        val early = member("가입순", created = midnight.minusSeconds(60))
        val late = member("가입순", created = midnight)
        assertThat(json(get("/members?query=가입순&order=asc").expectStatus().isOk)["items"][0]["memberId"].asText()).isEqualTo(early.publicId.toString())
        assertThat(json(get("/members?query=가입순&order=desc").expectStatus().isOk)["items"][0]["memberId"].asText()).isEqualTo(late.publicId.toString())
        socials.save(SocialAccount(userId = early.id, provider = SocialProvider.GOOGLE, providerUserId = "removed-link", email = "removed@example.test", deletedAt = now))
        assertThat(json(get("/members?query=removed@example.test").expectStatus().isOk)["totalElements"].asLong()).isZero()
    }

    @Test fun `감사 페이지의 동률은 공개 UUID로 고정하고 과거 처리자의 현재 상태를 보여 준다`() {
        val actor = member("탈퇴 처리자", UserStatus.DELETED)
        val low = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val high = UUID.fromString("00000000-0000-4000-8000-000000000002")
        for (id in listOf(high, low)) {
            jdbc.update("""INSERT INTO admin_audit_logs(public_id, admin_user_id, category, action, target_type, target_id, request_id, created_at)
                VALUES (?, ?, 'ACCESS', 'MEMBER_VIEWED', 'MEMBER', ?, 'fixture', ?)""", id, actor.id, actor.publicId.toString(), java.sql.Timestamp.from(now))
        }
        val first = json(get("/audit-logs?size=1").expectStatus().isOk)
        assertThat(first["items"][0]["auditLogId"].asText()).isEqualTo(low.toString())
        assertThat(first["items"][0]["admin"]["status"].asText()).isEqualTo("DELETED")
        assertThat(first["items"][0]["admin"]["isAdmin"].asBoolean()).isFalse()
        assertThat(first["totalPages"].asLong()).isEqualTo(2)
        assertThat(json(get("/audit-logs?size=1&page=2").expectStatus().isOk)["items"][0]["auditLogId"].asText()).isEqualTo(high.toString())
        val older = UUID.randomUUID()
        jdbc.update("""INSERT INTO admin_audit_logs(public_id, admin_user_id, category, action, target_type, target_id, request_id, created_at)
            VALUES (?, ?, 'ACCESS', 'MEMBER_VIEWED', 'MEMBER', ?, 'fixture', ?)""", older, actor.id, actor.publicId.toString(), java.sql.Timestamp.from(midnight))
        assertThat(json(get("/audit-logs?order=asc").expectStatus().isOk)["items"][0]["auditLogId"].asText()).isEqualTo(older.toString())
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(3)
    }

    @Test fun `검색어 없는 회원 전체 목록도 페이지마다 ACCESS 기록을 남긴다`() {
        repeat(22) { member("목록$it") }
        val first = json(get("/members").expectStatus().isOk)
        assertThat(first["items"].size()).isEqualTo(20)
        assertThat(first["totalElements"].asLong()).isEqualTo(23)
        assertThat(first["totalPages"].asLong()).isEqualTo(2)
        assertThat(json(get("/members?page=2").expectStatus().isOk)["items"].size()).isEqualTo(3)
        assertThat(jdbc.queryForList("SELECT target_id FROM admin_audit_logs", String::class.java)).containsExactly("ALL", "ALL")
    }

    @Test fun `결제는 완료와 환불만 페이지로 조회하고 상태와 KST 양끝 날짜를 필터한다`() {
        order(completed = midnight.minusSeconds(1))
        val included = order(completed = midnight)
        order(completed = midnight.plusSeconds(86400).minusSeconds(1), status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED, refund = now)
        order(completed = midnight.plusSeconds(86400))
        order(completed = null, status = com.knk.manyak.credit.entity.CreditOrderStatus.PENDING)
        val body = json(get("/payments/orders?from=2026-10-21&to=2026-10-21&size=1").expectStatus().isOk)
        assertThat(body["totalElements"].asLong()).isEqualTo(2)
        assertThat(body["totalPages"].asInt()).isEqualTo(2)
        assertThat(body["items"][0]["status"].asText()).isEqualTo("REFUNDED")
        val two = json(get("/payments/orders?from=2026-10-21&to=2026-10-21&size=1&page=2").expectStatus().isOk)
        assertThat(two["items"][0]["orderId"].asText()).isEqualTo(included.publicId.toString())
        assertThat(two["items"][0]["member"]["isAdmin"].asBoolean()).isTrue()
        assertThat(two["items"][0]["refundedAt"].isNull).isTrue()
        assertThat(two["items"][0]["reversalShortfall"].isNull).isTrue()
        assertThat(two["items"][0].has("userId")).isFalse()
        val only = json(get("/payments/orders?status=REFUNDED").expectStatus().isOk)
        assertThat(only["totalElements"].asLong()).isEqualTo(1)
        assertThat(json(get("/payments/orders?page=99").expectStatus().isOk)["items"].size()).isZero()
    }

    @Test fun `결제 정렬 네 열은 방향과 UUID 동률 및 NULL 후순위를 지킨다`() {
        val low = order(id = UUID.fromString("00000000-0000-4000-8000-000000000001"), refund = now,
            status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED)
        val high = order(id = UUID.fromString("00000000-0000-4000-8000-000000000002"), refund = now,
            status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED)
        for (sort in listOf("priceKrw", "creditAmount", "completedAt", "refundedAt")) {
            for (direction in listOf("asc", "desc")) {
                val first = json(get("/payments/orders?sort=$sort&order=$direction&size=1").expectStatus().isOk)
                val second = json(get("/payments/orders?sort=$sort&order=$direction&size=1&page=2").expectStatus().isOk)
                assertThat(first["items"][0]["orderId"].asText()).isEqualTo(low.publicId.toString())
                assertThat(second["items"][0]["orderId"].asText()).isEqualTo(high.publicId.toString())
            }
        }
        val earlierRefund = order(refund = now.minusSeconds(60), status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED)
        assertThat(json(get("/payments/orders?sort=refundedAt&order=asc").expectStatus().isOk)["items"][0]["orderId"].asText()).isEqualTo(earlierRefund.publicId.toString())
        assertThat(json(get("/payments/orders?sort=refundedAt&order=desc").expectStatus().isOk)["items"][0]["orderId"].asText()).isEqualTo(low.publicId.toString())
        orders.delete(earlierRefund)
        val distinct = order(price = 100, credit = 200, completed = midnight.minusSeconds(1))
        for (sort in listOf("priceKrw", "creditAmount", "completedAt")) {
            assertThat(json(get("/payments/orders?sort=$sort&order=asc").expectStatus().isOk)["items"][0]["orderId"].asText()).isEqualTo(distinct.publicId.toString())
            assertThat(json(get("/payments/orders?sort=$sort&order=desc").expectStatus().isOk)["items"][0]["orderId"].asText()).isEqualTo(low.publicId.toString())
        }
        for (direction in listOf("asc", "desc")) {
            assertThat(json(get("/payments/orders?sort=refundedAt&order=$direction").expectStatus().isOk)["items"][2]["orderId"].asText()).isEqualTo(distinct.publicId.toString())
        }
    }

    @Test fun `회원 상세 잔액은 만료 로트를 빼고 콘텐츠 수는 삭제된 행을 뺀다`() {
        val user = member("잔액 대상")
        lots.save(com.knk.manyak.credit.entity.CreditLot(userId = user.id, originalAmount = 300, remaining = 200, expiresAt = now.plusSeconds(1)))
        lots.save(com.knk.manyak.credit.entity.CreditLot(userId = user.id, originalAmount = 50, remaining = 50, expiresAt = now))
        val live = storyRepository.save(com.knk.manyak.story.entity.Story(userId = user.id, title = "보유 스토리"))
        storyRepository.save(com.knk.manyak.story.entity.Story(userId = user.id, title = "삭제 스토리", deletedAt = now))
        chatRepository.save(com.knk.manyak.chat.entity.StoryChat(userId = user.id, storyId = live.id))
        chatRepository.save(com.knk.manyak.chat.entity.StoryChat(userId = user.id, storyId = live.id, deletedAt = now))
        val body = json(get("/members/${user.publicId}").expectStatus().isOk)
        assertThat(body["creditBalance"].asLong()).isEqualTo(200)
        assertThat(body["storyCount"].asLong()).isEqualTo(1)
        assertThat(body["chatCount"].asLong()).isEqualTo(1)
    }

    @Test fun `실제 회원 조회도 접속 기록 저장 실패면 개인정보 응답 대신 500이다`() {
        val user = member("개인정보")
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("injected"))
            .`when`(auditRepository).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java)
                ?: com.knk.manyak.admin.entity.AdminAuditLog(adminUserId = admin.id,
                    category = com.knk.manyak.admin.entity.AdminAuditCategory.ACCESS,
                    action = com.knk.manyak.admin.entity.AdminAuditAction.MEMBER_VIEWED,
                    targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.MEMBER, targetId = "fixture", requestId = "fixture"))
        get("/members?query=개인정보").expectStatus().is5xxServerError.expectBody().jsonPath("$.items").doesNotExist()
        get("/members/${user.publicId}").expectStatus().is5xxServerError.expectBody().jsonPath("$.memberId").doesNotExist()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `감사 로그는 현재 처리자 요약과 JSON 전후 값 및 필터를 페이지로 반환한다`() {
        get("/members/${admin.publicId}").expectStatus().isOk
        val target = UUID.randomUUID().toString()
        org.slf4j.MDC.put("request_id", "req_fixture")
        try {
            org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
                audits.recordChange(admin.id, com.knk.manyak.admin.entity.AdminAuditAction.CREDIT_GRANTED, target, "테스트",
                    mapper.readTree("{\"balance\":1}"), mapper.readTree("{\"balance\":2}"))
            }
        } finally { org.slf4j.MDC.clear() }
        val request = "/audit-logs?adminId=${admin.publicId}&action=CREDIT_GRANTED&targetType=MEMBER&targetId=$target&size=1"
        val body = json(get(request).expectStatus().isOk)
        assertThat(body["totalElements"].asLong()).isEqualTo(1)
        assertThat(body["items"][0]["before"]["balance"].asInt()).isEqualTo(1)
        assertThat(body["items"][0]["after"]["balance"].asInt()).isEqualTo(2)
        assertThat(body["items"][0]["admin"]["memberId"].asText()).isEqualTo(admin.publicId.toString())
        assertThat(body["items"][0]["admin"].has("id")).isFalse()
        assertThat(body["items"][0]["requestId"].asText()).isEqualTo("req_fixture")
        for (filter in listOf("adminId=${UUID.randomUUID()}", "action=NOTICE_CREATED", "targetType=NOTICE", "targetType=MEMBER&targetId=missing")) {
            assertThat(json(get("/audit-logs?$filter").expectStatus().isOk)["totalElements"].asLong()).isZero()
        }
        assertThat(json(get("$request&page=2").expectStatus().isOk)["items"].size()).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(2)
    }

    @Test fun `대시보드는 KST 경계와 사유별 합계를 지키고 팀과 게스트를 구분한다`() {
        val ordinary = member("일반", created = midnight.plusSeconds(60))
        val team = member("팀", created = midnight.plusSeconds(60))
        val before = member("어제", created = midnight.minusSeconds(60))
        // 테스트 관리자도 일반 집계에서 제외해 각 집계의 기대값을 명확히 한다.
        val excluded = listOf(team.id, admin.id)
        for (owner in listOf<Long?>(ordinary.id, team.id, null)) {
            val story = storyRepository.save(com.knk.manyak.story.entity.Story(userId = owner, title = "집계", createdAt = midnight, deletedAt = now))
            val chat = chatRepository.save(com.knk.manyak.chat.entity.StoryChat(userId = owner, storyId = story.id, createdAt = midnight, deletedAt = now))
            repeat(3) { n ->
                val message = messageRepository.save(com.knk.manyak.chat.entity.StoryMessage(chatId = chat.id,
                    role = com.knk.manyak.chat.entity.MessageRole.ASSISTANT, content = "응답", messageOrder = n + 1, createdAt = midnight))
                if (n == 0) repeat(2) { v -> versions.save(com.knk.manyak.chat.entity.StoryMessageVersion(
                    messageId = message.id, versionNumber = v + 1, content = "이전 응답", choices = "[]")) }
            }
            messageRepository.save(com.knk.manyak.chat.entity.StoryMessage(chatId = chat.id,
                role = com.knk.manyak.chat.entity.MessageRole.USER, content = "입력", messageOrder = 4, createdAt = midnight))
        }
        for (owner in listOf(ordinary, team)) {
            for ((amount, reason) in listOf(250L to "ATTENDANCE_REWARD", 5000L to "PURCHASE", 100L to "SIGNUP_REWARD",
                -20L to "CHAT_TURN", -30L to "EXPIRE", 20L to "REFUND")) {
                jdbc.update("INSERT INTO credit_transactions(user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)", owner.id, amount, reason, java.sql.Timestamp.from(midnight))
            }
            order(user = owner, completed = midnight, price = 4800)
            order(user = owner, completed = midnight.minusSeconds(1), price = 2000, status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED, refund = now)
        }
        val body = dashboardRepository.summary(now, midnight, excluded)
        assertThat(body.asOf).isEqualTo(now)
        assertThat(body.todayStartsAt).isEqualTo(midnight)
        assertThat(body.members.total).isEqualTo(2)
        assertThat(body.members.joinedToday).isEqualTo(1)
        assertThat(body.chats.startedTotal).isEqualTo(2)
        assertThat(body.chats.turnsTotal).isEqualTo(6)
        assertThat(body.chats.turnsToday).isEqualTo(6)
        assertThat(body.stories.total).isEqualTo(2)
        assertThat(body.credits.grantedTotal).isEqualTo(5350)
        assertThat(body.credits.spentTotal).isEqualTo(20)
        assertThat(body.credits.grantedToday).isEqualTo(5350)
        assertThat(body.credits.spentToday).isEqualTo(20)
        assertThat(body.stories.createdToday).isEqualTo(2)
        assertThat(body.chats.startedToday).isEqualTo(2)
        assertThat(body.payments.completedTotal).isEqualTo(2)
        assertThat(body.payments.completedToday).isEqualTo(1)
        assertThat(body.payments.amountKrwTotal).isEqualTo(6800)
        assertThat(body.payments.amountKrwToday).isEqualTo(4800)
        assertThat(dashboardRepository.summary(now, midnight, emptyList()).chats.startedTotal).isEqualTo(3)
        // 팀 계정 제외는 대시보드에만 적용하고 회원 검색에는 적용하지 않는다.
        assertThat(json(get("/members?query=팀").expectStatus().isOk)["totalElements"].asLong()).isEqualTo(1)
    }

    @Test fun `대시보드는 삭제된 카드를 응답하지 않고 활성과 정지 회원을 센다`() {
        member("정지", UserStatus.SUSPENDED)
        member("추가 활성")
        member("탈퇴", UserStatus.DELETED)
        val body = json(get("/dashboard/summary").expectStatus().isOk)
        assertThat(body["members"]["total"].asLong()).isEqualTo(3)
        assertThat(body["members"].has("withdrawnTotal")).isFalse()
        assertThat(body["members"].has("withdrawnToday")).isFalse()
        assertThat(body["payments"].has("refundedTotal")).isFalse()
        assertThat(body["payments"].has("refundedToday")).isFalse()
        assertThat(body.has("moderation")).isFalse()
        assertThat(body["asOf"].asText()).isEqualTo(now.toString())
        assertThat(body["todayStartsAt"].asText()).endsWith("15:00:00Z")
    }

    @Test fun `회원 검색은 정규화 닉네임과 공개 식별자와 정확한 활성 연동 이메일을 지원한다`() {
        val user = member("E Q U A L")
        socials.save(SocialAccount(userId = user.id, provider = SocialProvider.GOOGLE, providerUserId = "fixture", email = "lookup@example.test"))
        for (query in listOf("equal", "E Q U A L", user.publicId.toString(), "lookup@example.test")) {
            val body = json(get("/members?query=$query").expectStatus().isOk)
            assertThat(body["items"].size()).isEqualTo(1)
            assertThat(body["items"][0]["memberId"].asText()).isEqualTo(user.publicId.toString())
            assertThat(body["items"][0].has("email")).isFalse()
            assertThat(body["items"][0].has("id")).isFalse()
            assertThat(body["page"].asInt()).isEqualTo(1)
            assertThat(body["size"].asInt()).isEqualTo(20)
        }
        assertThat(json(get("/members?query=lookup@example").expectStatus().isOk)["totalElements"].asLong()).isZero()
        val rows = jdbc.queryForList("SELECT * FROM admin_audit_logs ORDER BY id")
        assertThat(rows).hasSize(5)
        assertThat(rows.first()["admin_user_id"]).isEqualTo(admin.id)
        assertThat(rows.first()["action"]).isEqualTo("MEMBER_SEARCHED")
        assertThat(rows.first()["target_id"]).isEqualTo("equal")
        assertThat(json(get("/members?query=${UUID.randomUUID()}").expectStatus().isOk)["totalElements"].asLong()).isZero()
    }

    @Test fun `회원 상태 필터와 공개 식별자 동률 정렬 및 페이지 경계를 적용한다`() {
        val low = member("동률", UserStatus.SUSPENDED, UUID.fromString("00000000-0000-4000-8000-000000000001"))
        val high = member("동률", UserStatus.SUSPENDED, UUID.fromString("00000000-0000-4000-8000-000000000002"))
        for (order in listOf("asc", "desc")) {
            val one = json(get("/members?query=동률&status=SUSPENDED&sort=createdAt&order=$order&size=1").expectStatus().isOk)
            assertThat(one["items"][0]["memberId"].asText()).isEqualTo(low.publicId.toString())
            assertThat(one["totalElements"].asLong()).isEqualTo(2)
            assertThat(one["totalPages"].asInt()).isEqualTo(2)
            val two = json(get("/members?query=동률&status=SUSPENDED&sort=createdAt&order=$order&size=1&page=2").expectStatus().isOk)
            assertThat(two["items"][0]["memberId"].asText()).isEqualTo(high.publicId.toString())
        }
        assertThat(json(get("/members?query=동률&page=99").expectStatus().isOk)["items"].size()).isZero()
    }

    @Test fun `탈퇴 회원은 닉네임 검색에서 숨기고 UUID와 상태 필터로 찾는다`() {
        val user = member("알 수 없음", UserStatus.DELETED)
        assertThat(json(get("/members?query=알").expectStatus().isOk)["totalElements"].asLong()).isZero()
        assertThat(json(get("/members?query=${user.publicId}&status=DELETED").expectStatus().isOk)["items"][0]["status"].asText()).isEqualTo("DELETED")
    }

    @Test fun `상세는 로트 기반 잔액과 콘텐츠 수를 응답하고 접속 기록을 남긴다`() {
        val user = member("대상")
        val body = json(get("/members/${user.publicId}").expectStatus().isOk)
        assertThat(body["creditBalance"].asLong()).isZero()
        assertThat(body["storyCount"].asLong()).isZero()
        assertThat(body["chatCount"].asLong()).isZero()
        assertThat(body.has("email")).isFalse()
        assertThat(body["deletedAt"].isNull).isTrue()
        val row = jdbc.queryForMap("SELECT * FROM admin_audit_logs")
        assertThat(row["action"]).isEqualTo("MEMBER_VIEWED")
        assertThat(row["target_id"]).isEqualTo(user.publicId.toString())
        assertThat(row["reason"]).isNull()
        assertThat(row["before_state"]).isNull()
        assertThat(row["after_state"]).isNull()
        get("/members/not-a-uuid").expectStatus().isBadRequest.expectBody().jsonPath("$.details[0].field").isEqualTo("memberId")
        get("/members/${UUID.randomUUID()}").expectStatus().isNotFound
    }

    @Test fun `페이지 정렬 상태와 검색어의 잘못된 값은 400이다`() {
        for (query in listOf("page=0", "page=-1", "page=bad", "page=2147483648", "cursor=obsolete", "limit=20", "size=0", "size=101", "size=bad",
            "sort=lastActiveAt", "order=ASC", "status=UNKNOWN", "query=", "query=${"a".repeat(51)}")) {
            get("/members?${if (query.startsWith("query=")) query else "query=대상&$query"}").expectStatus().isBadRequest
        }
        for (query in listOf("sort=bad", "order=bad", "status=PENDING", "from=bad", "from=2026-02-30", "from=2026-02-02&to=2026-01-01")) {
            get("/payments/orders?$query").expectStatus().isBadRequest
        }
        for (query in listOf("adminId=bad", "targetId=alone", "size=101", "action=BAD", "targetType=BAD")) {
            get("/audit-logs?$query").expectStatus().isBadRequest
        }
    }

    @Test fun `SQL 와일드카드는 닉네임 검색에서 문자로 취급한다`() {
        member("다른사람")
        member("100%_!")
        val body = json(client.get().uri { builder -> builder.path("/api/v1/admin/members").queryParam("query", "%_").build() }
            .header("Authorization", "Bearer $token").exchange().expectStatus().isOk)
        assertThat(body["items"].size()).isEqualTo(1)
        assertThat(body["items"][0]["nickname"].asText()).isEqualTo("100%_!")
    }
}

@org.springframework.boot.test.context.TestConfiguration
class AdminReadClockConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    fun adminReadClock(): java.time.Clock = java.time.Clock.fixed(Instant.parse("2026-10-20T15:30:00Z"), java.time.ZoneOffset.UTC)
}
