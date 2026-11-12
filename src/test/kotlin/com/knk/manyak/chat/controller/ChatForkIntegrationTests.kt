package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.dto.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.dto.ChatStatus
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.dto.MessageRole
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.credit.dto.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.*
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant
import java.util.UUID

class ChatForkIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    @Autowired private lateinit var choices: StoryChoiceRepository
    @Autowired private lateinit var versions: StoryMessageVersionRepository
    @Autowired private lateinit var summaries: StoryChatSummaryRepository
    @Autowired private lateinit var events: StoryChatMainEventRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var starts: StoryStartSettingRepository
    @Autowired private lateinit var mainEvents: StoryMainEventRepository
    @Autowired private lateinit var credits: CreditTransactionRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var adminCharts: com.knk.manyak.admin.repository.AdminChartsRepository
    @Autowired private lateinit var adminDashboard: com.knk.manyak.admin.repository.AdminDashboardRepository
    @Autowired private lateinit var adminContent: com.knk.manyak.admin.repository.AdminContentRepository
    @Autowired private lateinit var adminEngagement: com.knk.manyak.admin.repository.AdminChatEngagementRepository
    @Autowired private lateinit var generating: com.knk.manyak.chat.service.ChatGenerationStore
    private lateinit var member: User

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        member = users.save(User(nickname = "포크 소유자"))
    }

    private data class Fixture(val chat: StoryChat, val turns: List<StoryMessage>)
    private fun fixture(count: Int = 5, ended: Boolean = false, owner: Long? = member.id): Fixture {
        val story = stories.save(Story(userId = member.id, title = "포크 스토리"))
        val start = starts.save(StoryStartSetting(story = story, name = "시작", prologue = "현재 프롤로그"))
        val event = mainEvents.save(StoryMainEvent(story = story, name = "지난 사건", description = "설명", keySentence = "근거", sortOrder = 0))
        val chat = chats.save(StoryChat(userId = owner, storyId = story.id, startSettingId = start.id,
            personaNameSnapshot = "서린", personaDescriptionSnapshot = "탐험가", creationId = UUID.randomUUID(),
            storyPrologueSnapshot = "서린의 시작", title = "내 채팅", currentTurn = count, revision = 4, regeneratedCount = 2,
            modelCode = "AURORA", longResponse = true, targetMainEventId = event.id, targetProgressTurns = 2,
            occurredMainEventNamesSnapshot = listOf("지난 사건"), status = if (ended) ChatStatus.ENDED else ChatStatus.ACTIVE,
            reachedEndingId = if (ended) 777 else null, reachedEndingNameSnapshot = if (ended) "도달 엔딩" else null))
        events.save(StoryChatMainEvent(chatId = chat.id, mainEventId = event.id))
        messages.save(StoryMessage(chatId = chat.id, role = MessageRole.SYSTEM, content = "시스템 원문", messageOrder = 1))
        val turns = (1..count).map { turn ->
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력 $turn", messageOrder = turn * 3))
            val charge = credits.save(CreditTransaction(userId = member.id, amount = -3, reason = CreditReason.CHAT_TURN, refType = "CHAT_TURN", refId = chat.id))
            val ai = messages.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT,
                content = "응답 $turn [[https://example.com/shared-image.webp]]", messageOrder = turn * 3 + 1,
                creditTransactionId = charge.id, modelCode = "AURORA",
                reachedEndingId = if (ended && turn == count) 777 else null,
                reachedEndingNameSnapshot = if (ended && turn == count) "도달 엔딩" else null))
            choices.save(StoryChoice(chatId = chat.id, messageId = ai.id, choiceText = "선택 $turn", choiceOrder = 1,
                isSelected = turn < count, selectedAt = if (turn < count) Instant.EPOCH else null, isEdited = if (turn < count) true else null))
            ai
        }
        return Fixture(chat, turns)
    }

    private fun request(chatId: String, body: String, user: User? = member): RestTestClient.ResponseSpec = client.post()
        .uri("/api/v1/chats/$chatId/fork").headers { if (user != null) it.setBearerAuth(jwt.issueAccessToken(user.publicId)) }
        .contentType(MediaType.APPLICATION_JSON).body(body).exchange()

    private fun fork(f: Fixture, turn: Int): Pair<CreateChatResponse, StoryChat> {
        val result = request(f.chat.publicId.toString(), """{"turnId":${f.turns[turn - 1].id}}""")
            .expectStatus().isCreated.expectBody(CreateChatResponse::class.java).returnResult().responseBody!!
        return result to chats.findByPublicIdAndDeletedAtIsNull(UUID.fromString(result.id))!!
    }

    @ParameterizedTest @ValueSource(ints = [3, 5])
    fun `중간과 마지막 턴까지 메시지 선택지 진행 상태를 새 채팅으로 복사한다`(turn: Int) {
        val f = fixture()
        val beforeCharges = credits.count()
        val beforeReach = jdbc.queryForObject("SELECT COUNT(*) FROM user_story_ending_reaches", Long::class.java)
        clearInvocations(messages)
        val (response, copy) = fork(f, turn)
        verify(messages, times(1)).findByChatIdOrderByMessageOrderAsc(f.chat.id)
        assertNotEquals(f.chat.publicId.toString(), response.id)
        assertEquals(stories.findById(f.chat.storyId).orElseThrow().publicId.toString(), response.storyId)
        assertEquals("서린", response.persona!!.name)
        assertEquals("서린의 시작", response.prologue)
        assertEquals("AURORA", response.model)
        assertTrue(response.longResponse)
        assertTrue(response.suggestedInputs.isEmpty())
        assertEquals(f.chat.id, copy.forkedFromChatId)
        assertEquals(turn, copy.forkedFromTurn)
        assertEquals(turn, copy.currentTurn)
        assertEquals(0, copy.hiddenTurnCount)
        assertEquals(0, copy.regeneratedCount)
        assertEquals(0, copy.revision)
        assertEquals(member.id, copy.userId)
        assertEquals(f.chat.creationId, copy.creationId)
        assertEquals(f.chat.startSettingId, copy.startSettingId)
        assertEquals(f.chat.personaDescriptionSnapshot, copy.personaDescriptionSnapshot)
        assertEquals(f.chat.targetMainEventId, copy.targetMainEventId)
        assertEquals(f.chat.targetProgressTurns, copy.targetProgressTurns)
        assertEquals(f.chat.occurredMainEventNamesSnapshot, copy.occurredMainEventNamesSnapshot)
        assertEquals(events.findByChatId(f.chat.id).map { it.mainEventId }, events.findByChatId(copy.id).map { it.mainEventId })
        val originals = messages.findByChatIdOrderByMessageOrderAsc(f.chat.id).filter { it.messageOrder <= f.turns[turn - 1].messageOrder }
        val copied = messages.findByChatIdOrderByMessageOrderAsc(copy.id)
        assertEquals(originals.map { Triple(it.messageOrder, it.role, it.content) }, copied.map { Triple(it.messageOrder, it.role, it.content) })
        assertTrue(copied.all { it.creditTransactionId == null })
        assertEquals(originals.map { it.modelCode }, copied.map { it.modelCode })
        assertEquals(turn, copied.count { it.role == MessageRole.ASSISTANT })
        for (ai in copied.filter { it.role == MessageRole.ASSISTANT }) {
            val choice = choices.findByMessageIdOrderByChoiceOrderAsc(ai.id).single()
            assertEquals(copy.id, choice.chatId)
            assertEquals("선택 ${copied.filter { it.role == MessageRole.ASSISTANT }.indexOf(ai) + 1}", choice.choiceText)
            assertEquals(ai != copied.last() || turn < 5, choice.isSelected)
            assertEquals(if (choice.isSelected) Instant.EPOCH else null, choice.selectedAt)
            assertEquals(if (choice.isSelected) true else null, choice.isEdited)
        }
        assertEquals(beforeCharges, credits.count())
        assertEquals(beforeReach, jdbc.queryForObject("SELECT COUNT(*) FROM user_story_ending_reaches", Long::class.java))
        assertEquals(5, chats.findById(f.chat.id).orElseThrow().currentTurn)
    }

    @Test fun `버전 이력과 차감은 복사하지 않고 대상 이하의 유효 요약과 시스템 메시지만 복사한다`() {
        val f = fixture(25)
        versions.save(StoryMessageVersion(messageId = f.turns[0].id, versionNumber = 1, content = "이전 응답", choices = "[]", creditTransactionId = f.turns[0].creditTransactionId))
        summaries.save(StoryChatSummary(chatId = f.chat.id, throughTurn = 10, summary = "10턴 요약"))
        summaries.save(StoryChatSummary(chatId = f.chat.id, throughTurn = 20, summary = "20턴 요약"))
        summaries.save(StoryChatSummary(chatId = f.chat.id, throughTurn = 5, summary = "무효 요약", invalidatedAt = Instant.now()))
        val (_, copy) = fork(f, 15)
        assertEquals(listOf(10), summaries.findByChatIdOrderByThroughTurnAsc(copy.id).map { it.throughTurn })
        assertEquals("10턴 요약", summaries.findByChatIdOrderByThroughTurnAsc(copy.id).single().summary)
        val copied = messages.findByChatIdOrderByMessageOrderAsc(copy.id)
        assertEquals(1, copied.count { it.role == MessageRole.SYSTEM })
        assertEquals(31, copied.size)
        assertTrue(copied.all { versions.countByMessageId(it.id) == 0L && it.creditTransactionId == null })
    }

    @ParameterizedTest @ValueSource(ints = [3, 5])
    fun `도달 턴을 포함할 때만 엔딩을 복사한다`(turn: Int) {
        val f = fixture(ended = true)
        val (_, copy) = fork(f, turn)
        assertEquals(if (turn == 5) ChatStatus.ENDED else ChatStatus.ACTIVE, copy.status)
        assertEquals(if (turn == 5) 777L else null, copy.reachedEndingId)
        assertEquals(if (turn == 5) "도달 엔딩" else null, copy.reachedEndingNameSnapshot)
    }

    @Test fun `생성 중인 원본도 거절하지 않고 선점 상태를 바꾸지 않는다`() {
        val f = fixture()
        val active = generating.acquire(f.chat.publicId.toString(), "CONTINUE", "진행 중 입력", Instant.now(), null)
        try {
            val (_, copy) = fork(f, 3)
            assertEquals(3, copy.currentTurn)
            assertEquals(active.generationId, generating.get(f.chat.publicId.toString())!!.generationId)
        } finally {
            generating.release(f.chat.publicId.toString(), active.generationId)
        }
    }

    @Test fun `엔딩 FK가 사라져도 도달 이름 표식으로 ENDED를 복사한다`() {
        val f = fixture(ended = true)
        chats.save(f.chat.apply { reachedEndingId = null })
        jdbc.update("UPDATE story_messages SET reached_ending_id=NULL WHERE chat_id=?", f.chat.id)
        val (_, copy) = fork(f, 5)
        assertEquals(ChatStatus.ENDED, copy.status)
        assertNull(copy.reachedEndingId)
        assertEquals("도달 엔딩", copy.reachedEndingNameSnapshot)
    }

    @Test fun `기존 메시지 조회의 숨김 필터를 그대로 따르고 숨긴 대상 턴은 404다`() {
        val f = fixture()
        jdbc.update("UPDATE story_messages SET hidden_at=CURRENT_TIMESTAMP WHERE chat_id=? AND message_order IN (9, 10)", f.chat.id)
        chats.save(f.chat.apply { currentTurn = 4; hiddenTurnCount = 1 })
        assertEquals(4, messages.findByChatIdOrderByMessageOrderAsc(f.chat.id).count { it.role == MessageRole.ASSISTANT })
        request(f.chat.publicId.toString(), """{"turnId":${f.turns[2].id}}""").expectStatus().isNotFound
        val (_, copy) = fork(f, 5)
        assertEquals(4, copy.currentTurn)
        assertEquals(4, copy.forkedFromTurn)
        assertEquals(0, copy.hiddenTurnCount)
        assertEquals(7L, chats.sumTurnCountByStoryId(f.chat.storyId))
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM story_messages WHERE chat_id=? AND hidden_at IS NOT NULL", Long::class.java, copy.id))
        assertFalse(messages.findByChatIdOrderByMessageOrderAsc(copy.id).any { it.content.startsWith("응답 3 ") })
    }

    @Test fun `원본 삭제와 변경 이후에도 복사본의 메시지와 이미지가 유지되고 추가 턴만 누적된다`() {
        val f = fixture()
        val (_, copy) = fork(f, 3)
        assertEquals(7L, chats.sumTurnCountByStoryId(f.chat.storyId))
        chats.save(chats.findById(f.chat.id).orElseThrow().apply { currentTurn = 6; deletedAt = Instant.now() })
        messages.save(messages.findById(f.turns[0].id).orElseThrow().apply { content = "원본 수정" })
        assertTrue(messages.findByChatIdOrderByMessageOrderAsc(copy.id).any { it.content.contains("shared-image.webp") })
        assertTrue(messages.findByChatIdOrderByMessageOrderAsc(copy.id).any { it.content.startsWith("응답 1 ") })
        chats.save(copy.apply { currentTurn += 1 })
        assertEquals(9L, chats.sumTurnCountByStoryId(f.chat.storyId))
        assertEquals(6, chats.findById(f.chat.id).orElseThrow().currentTurn)
        val copyFirst = messages.findByChatIdOrderByMessageOrderAsc(copy.id).first { it.role == MessageRole.ASSISTANT }
        messages.save(copyFirst.apply { content = "복사본 수정" })
        assertEquals("원본 수정", messages.findById(f.turns[0].id).orElseThrow().content)
        val (_, grandchild) = fork(Fixture(copy, messages.findByChatIdOrderByMessageOrderAsc(copy.id).filter { it.role == MessageRole.ASSISTANT }), 2)
        assertEquals(2, grandchild.forkedFromTurn)
        assertEquals(9L, chats.sumTurnCountByStoryId(f.chat.storyId))
    }

    @Test fun `원본 채팅의 스토리 행이 없으면 404이고 복사본을 남기지 않는다`() {
        val f = fixture()
        // story_chats.story_id는 테스트 스키마(ddl-auto)에서 FK가 없어 없는 스토리를 가리키게 만들 수 있다.
        jdbc.update("UPDATE story_chats SET story_id=? WHERE id=?", 999_999_999L, f.chat.id)
        try {
            val before = chats.count()
            request(f.chat.publicId.toString(), """{"turnId":${f.turns[0].id}}""").expectStatus().isNotFound
            assertEquals(before, chats.count())
        } finally {
            // 없는 스토리를 가리키는 고아 행을 남기지 않는다.
            jdbc.update("UPDATE story_chats SET story_id=? WHERE id=?", f.chat.storyId, f.chat.id)
        }
    }
    @Test fun `회원 정지 소유권 대상과 본문 검증은 계약의 상태 코드를 반환한다`() {
        val f = fixture()
        val body = """{"turnId":${f.turns[0].id}}"""
        request(f.chat.publicId.toString(), body, null).expectStatus().isUnauthorized
        request(f.chat.publicId.toString(), body, users.save(User(nickname = "타인"))).expectStatus().isForbidden
        request(fixture(owner = null).chat.publicId.toString(), body).expectStatus().isForbidden
        listOf("{}", """{"turnId":0}""", """{"turnId":-1}""").forEach { request(f.chat.publicId.toString(), it).expectStatus().isBadRequest }
        request("wrong-id", body).expectStatus().isNotFound
        request(UUID.randomUUID().toString(), body).expectStatus().isNotFound
        request(f.chat.publicId.toString(), """{"turnId":9223372036854775807}""").expectStatus().isNotFound
        val other = fixture()
        request(f.chat.publicId.toString(), """{"turnId":${other.turns[0].id}}""").expectStatus().isNotFound
        chats.save(f.chat.apply { deletedAt = Instant.now() })
        request(f.chat.publicId.toString(), body).expectStatus().isNotFound
        users.save(member.apply { status = UserStatus.SUSPENDED })
        request(other.chat.publicId.toString(), """{"turnId":${other.turns[0].id}}""").expectStatus().isForbidden
    }

    @Test fun `관리자 현재 턴 합은 복사본을 포함하고 생성 응답 수는 복사를 빼고 숨긴 응답을 센다`() {
        val f = fixture()
        jdbc.update("UPDATE story_messages SET hidden_at=CURRENT_TIMESTAMP WHERE id=?", f.turns.last().id)
        chats.save(f.chat.apply { currentTurn = 4; hiddenTurnCount = 1 })
        val (_, copy) = fork(f, 3)
        val until = Instant.now().plusSeconds(60)
        assertEquals(5L, adminCharts.daily(Instant.EPOCH, until, emptyList()).values.sumOf { it["turns"] ?: 0L })
        assertEquals(5L, adminEngagement.turns(Instant.EPOCH, until, emptyList()).values.sum())
        assertEquals(setOf(f.chat.id), adminEngagement.activity(Instant.EPOCH, until, emptyList()).map { it.chatId }.toSet())
        val rows = adminContent.stories(com.knk.manyak.admin.dto.AdminStoriesRequest(),
            com.knk.manyak.adminkit.query.AdminPagination(1, 20, "s.created_at DESC"), null)
        // 현재 진행 깊이는 원본 4 + 복사본 3. 생성 응답 수 5와 정의가 다르다.
        assertEquals(7L, rows.items.single().totalTurns)
        val before = adminDashboard.summary(until, Instant.EPOCH, emptyList()).chats
        assertEquals(5L, before.turnsTotal)
        assertEquals(5L, before.turnsToday)
        messages.save(StoryMessage(chatId = copy.id, role = MessageRole.USER, content = "새 입력", messageOrder = 12))
        messages.save(StoryMessage(chatId = copy.id, role = MessageRole.ASSISTANT, content = "새 응답", messageOrder = 13))
        chats.save(copy.apply { currentTurn++ })
        val after = adminDashboard.summary(until, Instant.EPOCH, emptyList()).chats
        assertEquals(6L, after.turnsTotal)
        assertEquals(6L, after.turnsToday)
        assertEquals(6L, adminCharts.daily(Instant.EPOCH, until, emptyList()).values.sumOf { it["turns"] ?: 0L })
        assertEquals(6L, adminEngagement.turns(Instant.EPOCH, until, emptyList()).values.sum())
        assertEquals(setOf(f.chat.id, copy.id), adminEngagement.activity(Instant.EPOCH, until, emptyList()).map { it.chatId }.toSet())
    }

    @Test fun `CHAT 그룹 완료 식은 복사 턴을 별도로 빼지 않는 기존 계약을 따른다`() {
        val f = fixture()
        val (_, copy) = fork(f, 3)
        assertEquals(3L, credits.findLegacyChatCompletedCount(member.id, copy.id))
        chats.save(copy.apply { currentTurn += 1 })
        assertEquals(4L, credits.findLegacyChatCompletedCount(member.id, copy.id))
    }
}
