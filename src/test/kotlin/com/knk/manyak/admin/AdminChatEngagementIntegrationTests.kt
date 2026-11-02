package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Import(AdminReadClockConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.admin.dashboard.excluded-user-ids=987654321"])
class AdminChatEngagementIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    @Autowired private lateinit var versions: StoryMessageVersionRepository
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-20T15:30:00Z")
    private val yesterday = Instant.parse("2026-10-20T00:00:00Z")
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
        story = stories.saveAndFlush(Story(userId = admin.id, title = "몰입 fixture"))
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun user() = users.saveAndFlush(User(nickname = UUID.randomUUID().toString()))
    private fun chat(owner: User? = admin, deleted: Boolean = false) = chats.saveAndFlush(StoryChat(
        userId = owner?.id, storyId = story.id, createdAt = Instant.parse("2020-01-01T00:00:00Z"),
        deletedAt = if (deleted) now else null, regeneratedCount = 999))
    private fun message(chat: StoryChat, at: Instant = now, role: MessageRole = MessageRole.USER): StoryMessage =
        messages.saveAndFlush(StoryMessage(chatId = chat.id, role = role, content = "fixture",
            messageOrder = jdbc.queryForObject("SELECT COUNT(*) FROM story_messages WHERE chat_id=?", Long::class.java, chat.id)!!.toInt() + 1, createdAt = at))
    private fun version(message: StoryMessage, at: Instant = now) = versions.saveAndFlush(StoryMessageVersion(
        messageId = message.id, versionNumber = versions.countByMessageId(message.id).toInt() + 1,
        content = "old fixture", choices = "[]", createdAt = at))
    private fun response(query: String = "", auth: String? = token) = client.get().uri("/api/v1/admin/chats/engagement$query")
        .apply { auth?.let { header("Authorization", "Bearer $it") } }.exchange()
    private fun read(query: String = "") = mapper.readTree(response(query).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    @Test fun `일별 활성은 회원 사용자 메시지이고 턴은 게스트를 포함하는 차트 정의다`() {
        val a = chat(); val b = chat(user(), deleted = true); val guest = chat(null); val excluded = chat(team)
        repeat(2) { message(a); message(a, role = MessageRole.ASSISTANT) }
        message(b); message(b, role = MessageRole.ASSISTANT)
        message(guest); message(guest, role = MessageRole.ASSISTANT)
        message(excluded); message(excluded, role = MessageRole.ASSISTANT)
        message(chat(user()), role = MessageRole.SYSTEM)
        val body = read("?from=2026-10-21&to=2026-10-21")
        assertThat(body["activeMembers"].asLong()).isEqualTo(2)
        assertThat(body["monthlyActiveMembers"].asLong()).isEqualTo(2)
        assertThat(body["turnsPerActiveMember"].asDouble()).isEqualTo(2.0)
        val d = body["daily"][0]
        assertThat(d["activeMembers"].asLong()).isEqualTo(2)
        assertThat(d["turns"].asLong()).isEqualTo(4)
        assertThat(d["turnsPerActiveMember"].asDouble()).isEqualTo(2.0)
        val chart = mapper.readTree(client.get().uri("/api/v1/admin/dashboard/charts?from=2026-10-21&to=2026-10-21")
            .header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(chart["daily"][0]["turns"].asLong()).isEqualTo(d["turns"].asLong())
    }

    @Test fun `첫날 이어 가기는 범위 전날 같은 회원 채팅 쌍의 교집합이며 회원은 중복 없이 센다`() {
        val a = chat(); val a2 = chat(); val b = user(); val b1 = chat(b); val b2 = chat(b)
        for (c in listOf(a, a2, b1)) message(c, yesterday)
        for (c in listOf(a, a2, b2)) { message(c); message(c) }
        val d = read("?from=2026-10-21&to=2026-10-21")["daily"][0]
        assertThat(d["activeMembers"].asLong()).isEqualTo(2)
        assertThat(d["continuedMembers"].asLong()).isEqualTo(1)
        assertThat(d["continuationRate"].asDouble()).isEqualTo(0.5)
        assertThat(d["stickiness"].asDouble()).isEqualTo(1.0)
    }

    @Test fun `성공 재생성은 보관 시각의 판본 행을 세며 누적 카운터와 메시지 생성일을 쓰지 않는다`() {
        val old = Instant.parse("2020-01-01T00:00:00Z")
        val a = chat(deleted = true)
        val original = message(a, old, MessageRole.ASSISTANT)
        version(original); version(original)
        val guest = message(chat(null), old, MessageRole.ASSISTANT)
        version(guest)
        version(message(chat(team), old, MessageRole.ASSISTANT))
        version(original, yesterday); version(original, now.plusSeconds(1))
        repeat(40) { message(a, role = MessageRole.ASSISTANT) }
        val body = read("?from=2026-10-21&to=2026-10-21")
        val d = body["daily"][0]
        assertThat(d["regenerations"].asLong()).isEqualTo(3)
        assertThat(d["turns"].asLong()).isEqualTo(40)
        assertThat(d["regenerationRate"].asDouble()).isEqualTo(3.0 / 40)
        assertThat(d["turnsPerActiveMember"].isNull).isTrue()
        assertThat(body["turnsPerActiveMember"].isNull).isTrue()
    }

    @Test fun `월 활성과 고착도 창은 해당 날짜를 포함한 삼십 일이며 요청 범위 밖 활동도 읽는다`() {
        val first = Instant.parse("2026-09-21T15:00:00Z") // KST 09-22, 10-21의 30일 창 첫날
        message(chat(user()), first.minusNanos(1000)) // 09-21 제외
        val a = chat()
        message(a, first); message(a) // 창 안 중복 회원은 한 명
        message(chat(user()), first)
        val body = read("?from=2026-10-20&to=2026-10-21")
        assertThat(body["activeMembers"].asLong()).isEqualTo(1)
        assertThat(body["monthlyActiveMembers"].asLong()).isEqualTo(2)
        assertThat(body["daily"][0]["activeMembers"].asLong()).isZero()
        assertThat(body["daily"][0]["stickiness"].asDouble()).isZero()
        assertThat(body["daily"][1]["stickiness"].asDouble()).isEqualTo(0.5)
    }

    @Test fun `롤링 창에서 마지막 활동이 빠져도 다른 날짜 활동이 남은 회원은 유지한다`() {
        val a = chat(); val b = chat(user())
        message(a, Instant.parse("2026-09-20T15:00:00Z")) // 09-21
        message(a, Instant.parse("2026-09-21T15:00:00Z")) // 09-22
        message(b, Instant.parse("2026-09-20T15:00:00Z"))
        message(a, yesterday)
        val body = read("?from=2026-10-20&to=2026-10-21")
        assertThat(body["daily"][0]["stickiness"].asDouble()).isEqualTo(0.5)
        assertThat(body["monthlyActiveMembers"].asLong()).isEqualTo(1)
        assertThat(body["daily"][1]["stickiness"].asDouble()).isZero()
    }

    @Test fun `기간 고유 활성은 날짜 합이 아니고 월 활성은 to 기준이다`() {
        val a = chat(); val b = chat(user())
        message(a, yesterday); message(a); message(b)
        message(chat(user()), Instant.parse("2026-09-01T00:00:00Z"))
        val body = read("?from=2026-09-01&to=2026-10-21")
        assertThat(body["activeMembers"].asLong()).isEqualTo(3)
        assertThat(body["monthlyActiveMembers"].asLong()).isEqualTo(2)
        assertThat(body["daily"].sumOf { it["activeMembers"].asLong() }).isEqualTo(4)
    }

    @Test fun `KST 자정 마지막 마이크로초와 asOf 경계를 세고 미래 메시지와 판본을 제외한다`() {
        val a = chat()
        val before = Instant.parse("2026-10-20T14:59:59.999999Z")
        val midnight = Instant.parse("2026-10-20T15:00:00Z")
        for (at in listOf(before, midnight, now, now.plusNanos(1000))) {
            message(a, at)
            version(message(a, at, MessageRole.ASSISTANT), at)
        }
        val historical = read("?from=2026-10-20&to=2026-10-20")["daily"][0]
        assertThat(historical["turns"].asLong()).isEqualTo(1)
        assertThat(historical["regenerations"].asLong()).isEqualTo(1)
        val today = read("?from=2026-10-21&to=2026-10-21")["daily"][0]
        assertThat(today["turns"].asLong()).isEqualTo(2)
        assertThat(today["regenerations"].asLong()).isEqualTo(2)
        assertThat(today["continuedMembers"].asLong()).isEqualTo(1)
    }

    @Test fun `빈 날짜를 빠짐없이 채우고 분모 영 비율은 null이다`() {
        val body = read("?from=2026-10-18&to=2026-10-21")
        assertThat(body["daily"].toList().map { it["date"].asText() }).containsExactly("2026-10-18", "2026-10-19", "2026-10-20", "2026-10-21")
        assertThat(body["activeMembers"].asLong()).isZero()
        assertThat(body["monthlyActiveMembers"].asLong()).isZero()
        assertThat(body["turnsPerActiveMember"].isNull).isTrue()
        body["daily"].forEach { d ->
            for (key in listOf("activeMembers", "turns", "continuedMembers", "regenerations")) assertThat(d[key].asLong()).isZero()
            for (key in listOf("turnsPerActiveMember", "continuationRate", "regenerationRate", "stickiness")) assertThat(d[key].isNull).isTrue()
        }
    }

    @Test fun `기본 기간 기간 오류와 관리자 인증을 차트와 같이 처리한다`() {
        val body = read()
        assertThat(body["from"].asText()).isEqualTo("2026-09-22")
        assertThat(body["to"].asText()).isEqualTo("2026-10-21")
        assertThat(body["asOf"].asText()).isEqualTo(now.toString())
        assertThat(body["daily"].toList()).hasSize(30)
        for (days in listOf(7, 30, 90)) assertThat(read("?days=$days")["daily"].toList()).hasSize(days)
        assertThat(read("?from=2025-10-21&to=2026-10-21")["daily"].toList()).hasSize(366)
        for (q in listOf("days=10", "days=", "from=bad&to=bad", "from=2026-10-21", "to=2026-10-21",
            "from=2026-10-22&to=2026-10-22", "from=2025-10-20&to=2026-10-21", "from=2026-10-21&to=2026-10-20",
            "days=7&from=2026-10-20&to=2026-10-21")) response("?$q").expectStatus().isBadRequest
        response(auth = null).expectStatus().isUnauthorized
        response(auth = jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
    }
}
