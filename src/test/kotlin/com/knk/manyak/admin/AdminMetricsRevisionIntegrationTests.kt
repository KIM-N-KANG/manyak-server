package com.knk.manyak.admin

import com.knk.manyak.admin.analytics.AdminMetricsTestClock
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryMessageVersion
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryMessageVersionRepository
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@AutoConfigureRestTestClient
@Import(AdminMetricsTestClock::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.admin.dashboard.excluded-user-ids=900001,900002,900003"])
class AdminMetricsRevisionIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    @Autowired private lateinit var versions: StoryMessageVersionRepository
    private val mapper = ObjectMapper()
    private val start = Instant.parse("2026-10-20T15:00:00Z") // 10/21 KST 자정
    private val end = start.plusSeconds(86400)
    private val period = "from=2026-10-21&to=2026-10-21"
    private lateinit var token: String
    private lateinit var story: Story

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        fun user(id: Long): UUID {
            val publicId = UUID.randomUUID()
            jdbc.update("""INSERT INTO users(id,public_id,nickname,status,created_at,updated_at,migration_attempts,service_push_enabled)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,true)""", id, publicId, "회원$id")
            return publicId
        }
        val admin = user(900001)
        user(101)
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (900001,CURRENT_TIMESTAMP)")
        token = jwt.issueAdminToken(admin)
        story = stories.save(Story(title = "지표 개정", genre = "판타지"))
    }

    private fun get(path: String): JsonNode = mapper.readTree(client.get().uri("/api/v1/admin$path")
        .header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    private fun chat(turn: Int = 0, owner: Long? = null, model: String = "LITE", at: Instant = start,
                     deleted: Instant? = null, persona: String? = null) = chats.save(StoryChat(
        storyId = story.id, userId = owner, currentTurn = turn, modelCode = model, createdAt = at,
        deletedAt = deleted, personaNameSnapshot = persona))

    @Test fun `턴 도달률은 열두 기준 턴 경계와 세 구간 및 팀 삭제 기간 제외를 따른다`() {
        val thresholds = listOf(1, 2, 3, 4, 5, 6, 7, 10, 15, 20, 30, 50)
        val turns = listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 14, 15, 19, 20, 29, 30, 49, 50)
        turns.forEach { chat(turn = it, owner = if (it % 2 == 0) null else 101L) }
        chat(turn = 100, owner = 900001)
        chat(turn = 100, deleted = end)
        chat(turn = 100, at = start.minusMillis(1))
        chat(turn = 100, at = end)
        val segments = get("/conversion/turn-reach?$period")["segments"]
        assertThat(segments.toList().map { it["segment"].asText() }).containsExactly("all", "guest", "member")
        for (segment in segments) {
            val included = turns.filter { when (segment["segment"].asText()) {
                "guest" -> it % 2 == 0
                "member" -> it % 2 != 0
                else -> true
            } }
            assertThat(segment["chats"].asInt()).isEqualTo(included.size)
            assertThat(segment["reached"].toList().map { it["turn"].asInt() }).containsExactlyElementsOf(thresholds)
            for (point in segment["reached"]) {
                val count = included.count { it >= point["turn"].asInt() }
                assertThat(point["chats"].asInt()).isEqualTo(count)
                assertThat(point["rate"].asDouble()).isEqualTo(count.toDouble() / included.size)
            }
        }
        val empty = get("/conversion/turn-reach?from=2026-10-19&to=2026-10-19")
        for (segment in empty["segments"]) {
            assertThat(segment["chats"].asLong()).isZero()
            assertThat(segment["reached"].toList().map { it["turn"].asInt() }).containsExactlyElementsOf(thresholds)
            assertThat(segment["reached"].toList().map { it["chats"].asLong() }).containsOnly(0L)
            assertThat(segment["reached"].toList().map { it["rate"].asDouble() }).containsOnly(0.0)
        }
    }

    @Test fun `모델별 턴은 메시지 KST 날짜로 세며 이전 채팅 삭제 채팅과 재생성 팀 제외를 차트와 맞춘다`() {
        val aurora = chat(owner = 101, model = "AURORA", persona = "이름")
        val old = chat(at = start.minusSeconds(86400))
        val deleted = chat(deleted = end)
        val team = chat(owner = 900001)
        val guest = chat(model = "AURORA")
        chat() // 채팅은 있지만 응답은 없는 행
        val orders = mutableMapOf<Long, Int>()
        fun message(chat: StoryChat, at: Instant = start, role: MessageRole = MessageRole.ASSISTANT,
                    model: String? = if (role == MessageRole.ASSISTANT) chat.modelCode else null): StoryMessage {
            val order = (orders[chat.id] ?: 0) + 1
            orders[chat.id] = order
            val saved = messages.save(StoryMessage(chatId = chat.id, role = role, content = "테스트", messageOrder = order, createdAt = at))
            jdbc.update("UPDATE story_messages SET model_code = ? WHERE id = ?", model, saved.id)
            return saved
        }
        val original = message(aurora)
        repeat(2) { versions.save(StoryMessageVersion(messageId = original.id, versionNumber = it + 1,
            content = "이전 판본", choices = "[]", createdAt = start.plusSeconds(100))) }
        message(aurora, end.minusMillis(1))
        repeat(3) { message(old, start.plusSeconds(it.toLong())) }
        message(deleted)
        message(team)
        message(guest, model = "LITE")
        message(aurora, model = null)
        message(aurora, role = MessageRole.USER)
        message(aurora, role = MessageRole.SYSTEM)
        message(aurora, start.minusMillis(1))
        message(old, end)

        val day = get("/quality/chat-options?$period")["daily"].single()
        assertThat(day["chats"].asLong()).isEqualTo(3)
        assertThat(day["withPersona"].asLong()).isEqualTo(1)
        assertThat(day["byModel"].properties().associate { it.key to it.value.asLong() })
            .containsExactlyInAnyOrderEntriesOf(mapOf("AURORA" to 2L, "LITE" to 1L))
        assertThat(day["turnsByModel"].properties().associate { it.key to it.value.asLong() })
            .containsExactlyInAnyOrderEntriesOf(mapOf("AURORA" to 2L, "LITE" to 5L, "UNRECORDED" to 1L))
        val charts = get("/dashboard/charts?$period")
        assertThat(day["turnsByModel"].properties().sumOf { it.value.asLong() }).isEqualTo(charts["daily"].single()["turns"].asLong()).isEqualTo(8)

        val daily = get("/quality/chat-options?from=2026-10-19&to=2026-10-22")["daily"]
        assertThat(daily.toList().map { it["date"].asText() }).containsExactly("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22")
        assertThat(daily[0]["turnsByModel"].isObject).isTrue()
        assertThat(daily[0]["turnsByModel"].size()).isZero()
        assertThat(daily[1]["turnsByModel"].properties().associate { it.key to it.value.asLong() }).isEqualTo(mapOf("AURORA" to 1L))
        assertThat(daily[3]["chats"].asLong()).isZero()
        assertThat(daily[3]["byModel"].size()).isZero()
        assertThat(daily[3]["turnsByModel"].properties().associate { it.key to it.value.asLong() }).isEqualTo(mapOf("LITE" to 1L))
    }

    @Test fun `채팅 모델 변경은 byModel만 재분류하고 과거 턴의 청구 모델은 유지한다`() {
        val chat = chat(owner = 101)
        repeat(3) { index ->
            val message = messages.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT,
                content = "모델 변경 전", messageOrder = index + 1, createdAt = start))
            jdbc.update("UPDATE story_messages SET model_code = 'LITE' WHERE id = ?", message.id)
        }
        val before = get("/quality/chat-options?$period")["daily"].single()
        assertThat(before["byModel"]["LITE"].asLong()).isEqualTo(1)
        assertThat(before["turnsByModel"]["LITE"].asLong()).isEqualTo(3)
        jdbc.update("UPDATE story_chats SET model_code = 'AURORA' WHERE id = ?", chat.id)
        val after = get("/quality/chat-options?$period")["daily"].single()
        assertThat(after["byModel"].properties().associate { it.key to it.value.asLong() }).isEqualTo(mapOf("AURORA" to 1L))
        assertThat(after["turnsByModel"]).isEqualTo(before["turnsByModel"])
        assertThat(get("/dashboard/charts?$period")["daily"].single()["turns"].asLong()).isEqualTo(3)
    }
}
