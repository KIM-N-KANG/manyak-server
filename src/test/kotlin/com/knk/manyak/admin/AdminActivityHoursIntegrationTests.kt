package com.knk.manyak.admin

import com.knk.manyak.auth.dto.SocialProvider
import com.knk.manyak.auth.dto.UserStatus
import com.knk.manyak.auth.entity.*
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.*
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.credit.entity.*
import com.knk.manyak.credit.repository.CreditOrderRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.AdminMetricsIntegrationTest
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.UUID

class AdminActivityHoursIntegrationTests : AdminMetricsIntegrationTest() {
    override val clockAt: java.time.Instant = java.time.Instant.parse("2026-11-03T11:10:00Z")
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var socials: SocialAccountRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    @Autowired private lateinit var orders: CreditOrderRepository
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-11-03T11:10:00Z")
    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private lateinit var admin: User
    private lateinit var team: User
    private lateinit var story: Story
    private lateinit var token: String
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = user()
        val seed = user()
        jdbc.update("UPDATE users SET id=987654321 WHERE id=?", seed.id)
        team = users.findById(987654321).orElseThrow()
        story = story(at = old)
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun user(at: Instant = old, status: UserStatus = UserStatus.ACTIVE) = users.saveAndFlush(
        User(nickname = UUID.randomUUID().toString(), createdAt = at, status = status))
    private fun social(user: User, provider: SocialProvider = SocialProvider.GOOGLE, deleted: Boolean = false) = socials.saveAndFlush(
        SocialAccount(userId = user.id, provider = provider, providerUserId = UUID.randomUUID().toString(), deletedAt = if (deleted) now else null))
    private fun story(owner: User? = admin, at: Instant = now, deleted: Boolean = false) = stories.saveAndFlush(
        Story(userId = owner?.id, title = "시간대 fixture", createdAt = at, deletedAt = if (deleted) now else null))
    private fun chat(owner: User? = admin, deleted: Boolean = false, source: StoryChat? = null) = chats.saveAndFlush(StoryChat(
        storyId = story.id, userId = owner?.id, deletedAt = if (deleted) now else null,
        forkedFromChatId = source?.id, forkedFromTurn = if (source == null) 0 else 2, createdAt = old))
    private fun message(chat: StoryChat, at: Instant = now, role: MessageRole = MessageRole.USER, hidden: Boolean = false) = messages.saveAndFlush(
        StoryMessage(chatId = chat.id, role = role, content = "fixture", createdAt = at, hiddenAt = if (hidden) now else null,
            messageOrder = jdbc.queryForObject("SELECT COUNT(*)+1 FROM story_messages WHERE chat_id=?", Int::class.java, chat.id)!!))
    private fun order(at: Instant? = now, owner: User = admin, status: CreditOrderStatus = CreditOrderStatus.COMPLETED, created: Instant = old) =
        orders.saveAndFlush(CreditOrder(userId = owner.id, productId = "if_2000", provider = CreditOrderProvider.GROBLE,
            status = status, priceKrw = 2000, creditAmount = 2000, createdAt = created, completedAt = at))
    private fun response(query: String = "", auth: String? = token) = client.get().uri("/api/v1/admin/users/activity-hours$query")
        .apply { auth?.let { header("Authorization", "Bearer $it") } }.exchange()
    private fun read(query: String = "") = mapper.readTree(response(query).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    private fun cell(metric: JsonNode, weekday: Int, hour: Int) = metric["cells"].toList().single { it["weekday"].asInt() == weekday && it["hour"].asInt() == hour }

    @Test fun `KST 월요일 0시와 23시 회원 가입 칸을 구한다`() {
        social(user(Instant.parse("2026-10-25T15:00:00Z")))
        social(user(Instant.parse("2026-10-26T14:59:59Z")))
        val m = read("?from=2026-10-26&to=2026-10-26")["metrics"]["signups"]
        assertThat(cell(m, 1, 0)["count"].asLong()).isEqualTo(1)
        assertThat(cell(m, 1, 23)["count"].asLong()).isEqualTo(1)
    }
    @Test fun `일요일 23시와 월요일 0시 메시지는 서로 다른 칸이다`() {
        val c = chat()
        message(c, Instant.parse("2026-11-01T14:59:59Z")); message(c, Instant.parse("2026-11-01T15:00:00Z"))
        val m = read("?from=2026-11-01&to=2026-11-02")["metrics"]["messages"]
        assertThat(cell(m, 7, 23)["count"].asLong()).isEqualTo(1)
        assertThat(cell(m, 1, 0)["count"].asLong()).isEqualTo(1)
    }
    @Test fun `KST 범위 앞과 다음 자정의 메시지는 제외한다`() {
        val c = chat()
        message(c, Instant.parse("2026-10-25T14:59:59.999999Z")); message(c, Instant.parse("2026-10-26T15:00:00Z"))
        assertThat(read("?from=2026-10-26&to=2026-10-26")["metrics"]["messages"]["total"].asLong()).isZero()
    }
    @Test fun `30일의 요일 날짜 수와 칸 평균은 요일마다 다른 분모를 쓴다`() {
        val c = chat()
        repeat(10) { message(c, Instant.parse("2026-10-01T12:00:00Z")) }
        repeat(8) { message(c, Instant.parse("2026-10-05T12:00:00Z")) }
        val b = read("?from=2026-10-01&to=2026-10-30")
        assertThat(b["totalDays"].asInt()).isEqualTo(30)
        assertThat(b["weekdayDays"].toList().map { it["days"].asInt() }).containsExactly(4, 4, 4, 5, 5, 4, 4)
        val m = b["metrics"]["messages"]
        assertThat(cell(m, 4, 21)["average"].asDouble()).isEqualTo(2.0)
        assertThat(cell(m, 1, 21)["average"].asDouble()).isEqualTo(2.0)
    }
    @Test fun `요일과 시 합 및 하루 평균을 서로 다른 분모로 계산한다`() {
        val c = chat()
        repeat(7) { message(c, Instant.parse("2026-10-25T15:00:00Z")) }
        repeat(5) { message(c, Instant.parse("2026-10-26T12:00:00Z")) }
        repeat(2) { message(c, Instant.parse("2026-10-27T12:00:00Z")) }
        val m = read("?from=2026-10-26&to=2026-11-01")["metrics"]["messages"]
        assertThat(m["weekdays"][0]["count"].asLong()).isEqualTo(12)
        assertThat(m["weekdays"][0]["average"].asDouble()).isEqualTo(12.0)
        assertThat(m["hours"][21]["count"].asLong()).isEqualTo(7)
        assertThat(m["hours"][21]["average"].asDouble()).isEqualTo(1.0)
        assertThat(m["total"].asLong()).isEqualTo(14)
        assertThat(m["dailyAverage"].asDouble()).isEqualTo(2.0)
    }
    @Test fun `짧은 범위의 없는 요일 평균은 null이며 시 평균은 전체 날짜 수로 나눈다`() {
        val c = chat()
        for (at in listOf("2026-10-26T12:00:00Z", "2026-10-27T12:00:00Z", "2026-10-28T12:00:00Z")) message(c, Instant.parse(at))
        val b = read("?from=2026-10-26&to=2026-10-28")
        assertThat(b["weekdayDays"].toList().map { it["days"].asInt() }).containsExactly(1, 1, 1, 0, 0, 0, 0)
        val m = b["metrics"]["messages"]
        m["cells"].toList().filter { it["weekday"].asInt() >= 4 }.forEach { assertThat(it["average"].isNull).isTrue() }
        m["weekdays"].toList().drop(3).forEach { assertThat(it["average"].isNull).isTrue() }
        assertThat(m["hours"][21]["average"].asDouble()).isEqualTo(1.0)
    }
    @Test fun `366일의 작은 평균을 반올림하지 않는다`() {
        order()
        val m = read("?from=2025-11-03&to=2026-11-03")["metrics"]["payments"]
        assertThat(m["dailyAverage"].asDouble()).isEqualTo(1.0 / 366)
        assertThat(m["hours"][20]["average"].asDouble()).isEqualTo(1.0 / 366)
    }
    @Test fun `빈 지표도 168칸 7요일 24시를 고정 순서로 채운다`() {
        val b = read("?days=7")
        assertThat(b["weekdayDays"].toList().map { it["weekday"].asInt() }).containsExactly(1, 2, 3, 4, 5, 6, 7)
        assertThat(b["metrics"].propertyNames().toList()).containsExactly("signups", "messages", "stories", "payments")
        b["metrics"].properties().forEach { (_, m) ->
            assertThat(m["total"].asLong()).isZero(); assertThat(m["dailyAverage"].asDouble()).isZero()
            assertThat(m["cells"].toList()).hasSize(168); assertThat(m["weekdays"].toList()).hasSize(7); assertThat(m["hours"].toList()).hasSize(24)
            assertThat(m["cells"].toList().map { it["weekday"].asInt() to it["hour"].asInt() }).containsExactlyElementsOf((1..7).flatMap { d -> (0..23).map { d to it } })
            assertThat(m["hours"].toList().map { it["hour"].asInt() }).containsExactlyElementsOf((0..23).toList())
            m["cells"].forEach { assertThat(it["count"].asLong()).isZero() }
        }
    }
    @Test fun `게스트 삭제 채팅 숨긴 USER 메시지를 세고 AI 응답은 세지 않는다`() {
        message(chat()); message(chat(null)); message(chat(deleted = true)); message(chat(), hidden = true)
        message(chat(), role = MessageRole.ASSISTANT)
        assertThat(read("?days=7")["metrics"]["messages"]["total"].asLong()).isEqualTo(4)
    }
    @Test fun `포크 복사 입력은 빼고 복사 AI 응답 뒤 새 입력만 센다`() {
        val source = chat()
        repeat(3) { message(source, old); message(source, old, MessageRole.ASSISTANT) }
        val fork = chat(source = source)
        repeat(2) { message(fork); message(fork, role = MessageRole.ASSISTANT) }
        message(fork)
        assertThat(read("?days=7")["metrics"]["messages"]["total"].asLong()).isEqualTo(1)
    }
    @Test fun `가입은 소셜 연동 없는 회원을 빼고 탈퇴 회원과 복수 연동을 한 번씩 센다`() {
        val active = user(now); social(active); social(active, SocialProvider.KAKAO)
        social(user(now, UserStatus.DELETED), deleted = true)
        user(now)
        assertThat(read("?days=7")["metrics"]["signups"]["total"].asLong()).isEqualTo(2)
    }
    @Test fun `스토리는 삭제와 게스트를 포함하고 결제는 완료 시각과 환불 여부를 따른다`() {
        story(); story(deleted = true); story(null)
        order(); order(status = CreditOrderStatus.REFUNDED); order(at = null, status = CreditOrderStatus.PENDING)
        val b = read("?days=7")["metrics"]
        assertThat(b["stories"]["total"].asLong()).isEqualTo(3)
        assertThat(b["payments"]["total"].asLong()).isEqualTo(2)
    }
    @Test fun `결제 칸은 생성이 아닌 완료 시각으로 정한다`() {
        order(Instant.parse("2026-10-26T15:05:00Z"), created = Instant.parse("2026-10-26T14:50:00Z"))
        val m = read("?from=2026-10-26&to=2026-10-27")["metrics"]["payments"]
        assertThat(cell(m, 2, 0)["count"].asLong()).isEqualTo(1)
        assertThat(cell(m, 1, 23)["count"].asLong()).isZero()
    }
    @Test fun `팀 계정은 회원 채팅 스토리 주문의 소유자로 제외한다`() {
        jdbc.update("UPDATE users SET created_at=? WHERE id=?", now.atOffset(ZoneOffset.UTC), team.id)
        social(team); message(chat(team)); story(team); order(owner = team)
        read("?days=7")["metrics"].properties().forEach { (_, m) -> assertThat(m["total"].asLong()).isZero() }
    }
    @Test fun `오늘도 날짜 수에 포함하고 asOf 뒤와 미래 시각은 영으로 둔다`() {
        val c = chat()
        message(c); message(c, now.plusNanos(1000)); message(c, Instant.parse("2026-11-03T12:00:00Z"))
        val b = read("?from=2026-11-03&to=2026-11-03")
        assertThat(b["totalDays"].asInt()).isEqualTo(1)
        assertThat(b["weekdayDays"][1]["days"].asInt()).isEqualTo(1)
        val m = b["metrics"]["messages"]
        assertThat(m["total"].asLong()).isEqualTo(1)
        assertThat(cell(m, 2, 20)["average"].asDouble()).isEqualTo(1.0)
        assertThat(cell(m, 2, 21)["count"].asLong()).isZero()
    }
    @Test fun `기본 최근30일과 허용 기간 오류 및 관리자 인증을 재사용한다`() {
        val b = read()
        assertThat(b["from"].asText()).isEqualTo("2026-10-05"); assertThat(b["to"].asText()).isEqualTo("2026-11-03")
        assertThat(b["asOf"].asText()).isEqualTo(now.toString())
        for (days in listOf(7, 30, 90)) assertThat(read("?days=$days")["totalDays"].asInt()).isEqualTo(days)
        for (q in listOf("days=14", "days=", "from=2025-11-02&to=2026-11-03", "from=2026-11-04&to=2026-11-04", "from=bad&to=bad", "from=2026-11-03", "to=2026-11-03", "from=2026-11-03&to=2026-11-02", "days=7&from=2026-11-01&to=2026-11-03")) response("?$q").expectStatus().isBadRequest
        response(auth = null).expectStatus().isUnauthorized
        response(auth = jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
        response(auth = jwt.issueAdminToken(user().publicId)).expectStatus().isForbidden
    }
    @Test fun `OpenAPI는 bearerAuth와 모든 응답 필드를 required로 표시한다`() {
        val api = mapper.readTree(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(api["paths"].has("/api/v1/admin/users/activity-hours")).isTrue()
        val op = api["paths"]["/api/v1/admin/users/activity-hours"]["get"]
        assertThat(op["security"].toString()).contains("bearerAuth")
        val schemas = api["components"]["schemas"]
        val names = schemas.propertyNames().toList().filter { it.startsWith("AdminActivityHours") }
        assertThat(names).isNotEmpty()
        names.forEach { name ->
            val schema = schemas[name]
            assertThat(schema["required"].toList().map { it.asText() }).containsExactlyInAnyOrderElementsOf(schema["properties"].propertyNames().toList())
        }
    }
}
