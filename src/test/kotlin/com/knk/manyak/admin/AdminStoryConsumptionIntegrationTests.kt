package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.story.entity.*
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "manyak.admin.dashboard.excluded-user-ids=987654321,987654322",
    "manyak.official-user-public-id=00000000-0000-4000-8000-000000000001",
])
class AdminStoryConsumptionIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-20T15:30:00Z")
    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private lateinit var admin: User
    private lateinit var official: User
    private lateinit var team: User
    private lateinit var token: String

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = user("소비 관리자")
        official = excluded("공식", 987654321, UUID.fromString("00000000-0000-4000-8000-000000000001"))
        team = excluded("팀", 987654322)
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun user(name: String = UUID.randomUUID().toString(), publicId: UUID = UUID.randomUUID()) =
        users.saveAndFlush(User(nickname = name, publicId = publicId))
    private fun excluded(name: String, id: Long, publicId: UUID = UUID.randomUUID()): User {
        val seed = user(name, publicId)
        jdbc.update("UPDATE users SET id=? WHERE id=?", id, seed.id)
        return users.findById(id).orElseThrow()
    }
    private fun story(owner: User? = admin, at: Instant = old, status: StoryStatus = StoryStatus.PUBLISHED,
                      visibility: StoryVisibility = StoryVisibility.PUBLIC, deleted: Boolean = false, id: UUID = UUID.randomUUID()) =
        stories.saveAndFlush(Story(userId = owner?.id, title = "스토리 $id", publicId = id,
            createdAt = at, status = status, visibility = visibility, deletedAt = if (deleted) now else null))
    private fun chat(story: Story, owner: User? = admin, turns: Int = 0, at: Instant = now, deleted: Boolean = false) =
        chats.saveAndFlush(StoryChat(storyId = story.id, userId = owner?.id, currentTurn = turns,
            createdAt = at, deletedAt = if (deleted) now else null))
    private fun message(chat: StoryChat, role: MessageRole = MessageRole.USER, at: Instant = now, order: Int = 1) =
        messages.saveAndFlush(StoryMessage(chatId = chat.id, role = role, content = "fixture", messageOrder = order, createdAt = at))
    private fun response(query: String = "", auth: String? = token) = client.get().uri("/api/v1/admin/stories/consumption$query")
        .apply { auth?.let { header("Authorization", "Bearer $it") } }.exchange()
    private fun read(query: String = "") = mapper.readTree(response(query).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    @Test fun `공개 수는 지금 공개 목록 조건을 적용하고 공식 계정을 포함한다`() {
        story(); story(); story(official)
        story(visibility = StoryVisibility.PRIVATE)
        story(status = StoryStatus.DRAFT)
        story(deleted = true)
        story(owner = null)
        story(team)
        val body = read("?from=2026-10-20&to=2026-10-20")
        assertThat(body["publicStories"].asLong()).isEqualTo(3)
        assertThat(body["officialStories"].asLong()).isEqualTo(1)
        assertThat(body["playedStories"].asLong()).isZero()
        assertThat(body["playedRate"].asDouble()).isZero()
        assertThat(read("?from=2020-01-01&to=2020-01-01")["publicStories"].asLong()).isEqualTo(3)
    }

    @Test fun `소비는 본인 게스트 삭제 팀 채팅을 제외하고 비공개 삭제 스토리를 순위에 포함한다`() {
        val s = story()
        val d = user(); val e = user()
        chat(s, admin, 999); chat(s, null, 999); chat(s, d, 999, deleted = true); chat(s, team, 999)
        chat(s, d, 10); chat(s, e, 20)
        chat(s, d, 999, now.plusSeconds(1)); chat(s, d, 999, old)
        val privateStory = story(visibility = StoryVisibility.PRIVATE)
        chat(privateStory, d, 7)
        val deleted = story(deleted = true)
        chat(deleted, d, 9)
        val body = read("?days=7")
        assertThat(body["otherMemberChats"].asLong()).isEqualTo(4)
        assertThat(body["playedStories"].asLong()).isEqualTo(1)
        assertThat(body["playedRate"].asDouble()).isEqualTo(1.0)
        val rows = body["ranking"].toList()
        assertThat(rows).hasSize(3)
        assertThat(rows[0]["storyId"].asText()).isEqualTo(s.publicId.toString())
        assertThat(rows[0]["chats"].asLong()).isEqualTo(2)
        assertThat(rows[0]["players"].asLong()).isEqualTo(2)
        assertThat(rows[0]["turns"].asLong()).isEqualTo(30)
        assertThat(rows.single { it["storyId"].asText() == deleted.publicId.toString() }["deleted"].asBoolean()).isTrue()
        assertThat(rows.single { it["storyId"].asText() == privateStory.publicId.toString() }["deleted"].asBoolean()).isFalse()
    }

    @Test fun `순위는 채팅 수 턴 합 공개 식별자 오름차순까지 안정적으로 정렬한다`() {
        val ids = listOf("ffffffff-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "7fffffff-0000-4000-8000-000000000001").map(UUID::fromString)
        val player = user()
        val fixtures = ids.map { story(id = it) }
        chat(fixtures[0], player, 80); chat(fixtures[1], player, 80); chat(fixtures[2], player, 50)
        val popular = story()
        repeat(2) { chat(popular, player) }
        val rows = read()["ranking"].toList()
        assertThat(rows.map { it["storyId"].asText() }).containsExactly(popular.publicId.toString(), ids[1].toString(), ids[0].toString(), ids[2].toString())
        assertThat(rows[0]["players"].asLong()).isEqualTo(1)
    }

    @Test fun `상위 집중도는 limit과 무관하게 상위 열 편을 전체 채팅으로 나눈다`() {
        val player = user()
        repeat(12) { val s = story(); repeat(10) { chat(s, player) } }
        val first = story()
        repeat(100) { chat(first, player) }
        val body = read("?limit=3")
        assertThat(body["otherMemberChats"].asLong()).isEqualTo(220)
        assertThat(body["topConcentration"].asDouble()).isEqualTo(190.0 / 220)
        assertThat(body["ranking"].toList()).hasSize(3)
        assertThat(body["ranking"][0]["chats"].asLong()).isEqualTo(100)
        assertThat(read("?limit=1")["topConcentration"].asDouble()).isEqualTo(190.0 / 220)
    }

    @Test fun `창작자는 삭제 무관 중복 없이 세고 직전 창작자 교집합과 활성 메시지를 계산한다`() {
        val active = (1..10).map { user() }
        val s = story()
        active.forEachIndexed { i, u -> message(chat(s, u, at = old, deleted = i == 0)) }
        val duplicate = chat(s, active[0]); message(duplicate); message(duplicate, order = 2)
        message(chat(s, null)); message(chat(s, team)); message(chat(s, official))
        message(chat(s, user()), MessageRole.ASSISTANT)
        message(chat(s, user()), MessageRole.SYSTEM)
        message(chat(s, user()), at = old)
        message(chat(s, user()), at = now.plusSeconds(1))
        val current = Instant.parse("2026-10-20T15:00:00Z")
        val previous = Instant.parse("2026-10-19T15:00:00Z")
        active.take(3).forEachIndexed { i, u -> story(u, at = current, deleted = i == 2) }
        story(active[0], at = current)
        (active.take(2) + active.drop(3).take(2)).forEach { story(it, at = previous) }
        story(official, at = current); story(official, at = previous); story(team, at = current)
        val c = read("?from=2026-10-21&to=2026-10-21")["creators"]
        assertThat(c["activeMembers"].asLong()).isEqualTo(10)
        assertThat(c["members"].asLong()).isEqualTo(3)
        assertThat(c["rate"].asDouble()).isEqualTo(0.3)
        assertThat(c["previousMembers"].asLong()).isEqualTo(4)
        assertThat(c["retained"].asLong()).isEqualTo(2)
        assertThat(c["retentionRate"].asDouble()).isEqualTo(0.5)
    }

    @Test fun `공식 계정 스토리는 공개 수와 순위에 남고 일반 팀 스토리와 팀 창작자는 제외한다`() {
        val a = story(official, at = now)
        val b = story(team, at = now)
        val player = user()
        repeat(5) { chat(a, player, 2) }
        chat(a, official); chat(a, team); chat(b, player)
        val body = read()
        assertThat(body["publicStories"].asLong()).isEqualTo(1)
        assertThat(body["officialStories"].asLong()).isEqualTo(1)
        assertThat(body["otherMemberChats"].asLong()).isEqualTo(5)
        assertThat(body["ranking"].toList()).hasSize(1)
        assertThat(body["ranking"][0]["official"].asBoolean()).isTrue()
        assertThat(body["creators"]["members"].asLong()).isZero()
    }

    @Test fun `기간과 직전 기간은 KST 자정 경계를 따르고 현재 미래 원장은 제외한다`() {
        val a = user(); val b = user(); val c = user()
        story(a, at = Instant.parse("2026-10-07T14:59:59.999999Z")) // 직전 기간 전
        story(b, at = Instant.parse("2026-10-07T15:00:00Z")) // 직전 10-08 시작
        story(c, at = Instant.parse("2026-10-14T14:59:59.999999Z")) // 직전 10-14 끝
        val s = story(c, at = Instant.parse("2026-10-14T15:00:00Z")) // 현재 10-15 시작
        story(a, at = now.plusSeconds(1))
        chat(s, a, at = Instant.parse("2026-10-14T14:59:59.999999Z"))
        chat(s, a, at = Instant.parse("2026-10-14T15:00:00Z"))
        val body = read("?days=7")
        assertThat(body["from"].asText()).isEqualTo("2026-10-15")
        assertThat(body["to"].asText()).isEqualTo("2026-10-21")
        assertThat(body["asOf"].asText()).isEqualTo(now.toString())
        assertThat(body["otherMemberChats"].asLong()).isEqualTo(1)
        val creators = body["creators"]
        assertThat(creators["members"].asLong()).isEqualTo(1)
        assertThat(creators["previousMembers"].asLong()).isEqualTo(2)
        assertThat(creators["retained"].asLong()).isEqualTo(1)
        assertThat(creators["rate"].isNull).isTrue()
    }

    @Test fun `빈 데이터와 기본값 기간 limit 검증 및 인증 계약을 지킨다`() {
        val body = read()
        assertThat(body["from"].asText()).isEqualTo("2026-09-22")
        assertThat(body["to"].asText()).isEqualTo("2026-10-21")
        assertThat(body["publicStories"].asLong()).isZero()
        assertThat(body["playedRate"].isNull).isTrue()
        assertThat(body["topConcentration"].isNull).isTrue()
        assertThat(body["ranking"].toList()).isEmpty()
        assertThat(body["creators"]["rate"].isNull).isTrue()
        assertThat(body["creators"]["retentionRate"].isNull).isTrue()
        for (limit in listOf(1, 50)) read("?limit=$limit")
        for (days in listOf(7, 30, 90)) read("?days=$days")
        read("?from=2025-10-21&to=2026-10-21")
        for (q in listOf("limit=0", "limit=51", "limit=abc", "days=10", "from=2026-10-21", "to=2026-10-21",
            "from=2026-10-22&to=2026-10-22", "from=2025-10-20&to=2026-10-21", "from=bad&to=bad",
            "from=2026-10-21&to=2026-10-20", "days=7&from=2026-10-20&to=2026-10-21")) response("?$q").expectStatus().isBadRequest
        response(auth = null).expectStatus().isUnauthorized
        response(auth = jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
    }

    @Test fun `기본 순위는 스무 편으로 제한하고 현재 공개 상태 변경을 반영한다`() {
        val player = user()
        val fixtures = (1..21).map { story() }
        fixtures.forEach { chat(it, player) }
        assertThat(read()["ranking"].toList()).hasSize(20)
        fixtures[0].visibility = StoryVisibility.PRIVATE
        stories.saveAndFlush(fixtures[0])
        val body = read("?limit=50")
        assertThat(body["publicStories"].asLong()).isEqualTo(20)
        assertThat(body["playedStories"].asLong()).isEqualTo(20)
        assertThat(body["ranking"].toList()).hasSize(21)
    }
}
