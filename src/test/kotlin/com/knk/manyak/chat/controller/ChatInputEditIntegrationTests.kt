package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.client.*
import com.knk.manyak.chat.dto.*
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.chat.service.*
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.*
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.ScriptedAi
import com.knk.manyak.support.ScriptedAiIntegrationTest
import org.assertj.core.api.Assertions.*
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ChatInputEditIntegrationTests : ScriptedAiIntegrationTest() {
    // 압축(compact)이 켜진 경로를 검증한다. 설정 빈은 공용 스파이라 테스트마다 켜고, 끝나면 자동 reset된다.
    @Autowired private lateinit var compactSettings: com.knk.manyak.chat.config.ChatCompactSettings

    @BeforeEach fun installScriptedAi() {
        org.mockito.Mockito.doReturn(true).`when`(compactSettings).enabled
        ScriptedAi.chat = scriptedChatInputEditTestConfigEditAi
        ScriptedAi.compact = scriptedChatInputEditTestConfigEditCompact
    }

    class Ai : ChatTurnAiClient {
        val requests = CopyOnWriteArrayList<ChatTurnAiRequest>()
        val links = CopyOnWriteArrayList<AiTraceLink>()
        var beforeResult: () -> Unit = {}
        override fun streamTurn(request: ChatTurnAiRequest, traceLink: AiTraceLink, deadline: Instant?, onCharacterImage: (ChatCharacterImageEvent) -> Unit, onToken: (String) -> Unit): ChatTurnAiResult {
            requests.add(request); links.add(traceLink); beforeResult()
            return ChatTurnAiResult(aiOutput = "수정 응답", choices = emptyList())
        }
        override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String, traceLink: AiTraceLink): ChatChoicesResult {
            requests.add(request); links.add(traceLink)
            return ChatChoicesResult(listOf("하나", "둘", "셋"))
        }
    }
    class Compact : ChatCompactAiClient {
        val requests = CopyOnWriteArrayList<ChatCompactAiRequest>()
        override fun compact(request: ChatCompactAiRequest, traceLink: AiTraceLink): String { requests.add(request); return "새 압축" }
    }
    @Autowired lateinit var http: RestTestClient
    @Autowired lateinit var chats: StoryChatRepository
    @Autowired lateinit var messages: StoryMessageRepository
    @Autowired lateinit var summaries: StoryChatSummaryRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var wallet: CreditWalletService
    @Autowired lateinit var trials: GuestTrialLimitService
    @Autowired lateinit var transactions: CreditTransactionRepository
    @Autowired lateinit var reconciliation: CreditReconciliationService
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var service: ChatService
    @Autowired lateinit var persister: ChatTurnPersister
    @Autowired lateinit var generation: ChatGenerationStore
    @Autowired lateinit var compactService: ChatCompactService
    private val ai = scriptedChatInputEditTestConfigEditAi
    private val compact = scriptedChatInputEditTestConfigEditCompact
    @Autowired lateinit var tm: org.springframework.transaction.PlatformTransactionManager
    @Autowired lateinit var reachRecorder: EndingReachRecorder
    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach fun reset() { cleaner.cleanAll(); ai.requests.clear(); ai.links.clear(); ai.beforeResult = {}; compact.requests.clear() }
    data class Fixture(val chat: StoryChat, val turns: List<StoryMessage>)
    private fun fixture(n: Int = 5, user: User? = null, model: String = "LITE", endingAt: Int? = null): Fixture {
        val story = stories.save(Story(title = "입력 수정", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id, userId = user?.id, currentTurn = n, modelCode = model,
            status = if (endingAt == null) ChatStatus.ACTIVE else ChatStatus.ENDED,
            reachedEndingNameSnapshot = if (endingAt == null) null else "기존 엔딩"))
        val turns = (1..n).map { turn ->
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "u$turn", messageOrder = turn * 3))
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.SYSTEM, content = "s$turn", messageOrder = turn * 3 + 1))
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "a$turn", messageOrder = turn * 3 + 2,
                reachedEndingNameSnapshot = if (turn == endingAt) "기존 엔딩" else null))
        }
        return Fixture(chat, turns)
    }
    private fun member() = users.save(User(nickname = UUID.randomUUID().toString().take(12), status = UserStatus.ACTIVE))
    private fun token(user: User) = jwt.issueAccessToken(user.publicId)
    private fun edit(f: Fixture, at: Int = 3, user: User? = null, input: String = "새 입력"): String {
        val request = http.post().uri("/api/v1/chats/${f.chat.publicId}/turns/edit/stream")
            .header("X-Manyak-Device-Id", "edit-${f.chat.id}").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
        if (user != null) request.header("Authorization", "Bearer ${token(user)}")
        return request.body("""{"turnId":${f.turns[at - 1].id},"userInput":"$input","userSource":"typed","realtimeImage":false}""")
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
    }
    private fun current(f: Fixture) = chats.findById(f.chat.id).orElseThrow()
    private fun visible(f: Fixture) = messages.findByChatIdOrderByMessageOrderAsc(f.chat.id)
    private fun assertClean(f: Fixture) { assertThat(current(f).revision).isZero(); assertThat(messages.findAll().filter { it.chatId == f.chat.id && it.hiddenAt != null }).isEmpty() }

    @Test fun `중간과 마지막 턴 수정은 숨김 수와 개정 횟수를 늘리고 새 ID를 저장한다`() {
        for (at in listOf(3, 5)) {
            val f = fixture()
            assertThat(edit(f, at)).contains("completed")
            assertThat(current(f).currentTurn).isEqualTo(at)
            assertThat(current(f).hiddenTurnCount).isEqualTo(6 - at)
            assertThat(current(f).revision).isEqualTo(1)
            assertThat(chats.sumTurnCountByStoryId(f.chat.storyId)).isEqualTo(6)
            val hidden = messages.findAll().filter { it.chatId == f.chat.id && it.hiddenAt != null }
            assertThat(hidden).hasSize((6 - at) * 3)
            assertThat(visible(f).last().id).isNotIn(f.turns.map { it.id })
            assertThat(ai.requests.last().history.map { it.content }).containsExactlyElementsOf((1 until at).flatMap { listOf("u$it", "a$it") })
        }
    }
    @Test fun `요약 10과 20에서 14턴 수정은 10 요약과 11부터 13 원문을 쓰고 20 요약을 무효화한다`() {
        val f = fixture(25)
        listOf(10, 20).forEach { summaries.save(StoryChatSummary(chatId = f.chat.id, throughTurn = it, summary = "$it 요약")) }
        edit(f, 14)
        assertThat(ai.requests.single().summary).isEqualTo("10 요약")
        assertThat(ai.requests.single().history.map { it.content }).containsExactly("u11", "a11", "u12", "a12", "u13", "a13")
        assertThat(ai.links.single().turnNumber).isEqualTo(14)
        val history = summaries.findByChatIdOrderByThroughTurnAsc(f.chat.id)
        assertThat(history[0].invalidatedAt).isNull(); assertThat(history[1].invalidatedAt).isNotNull()
        repeat(6) { append(f) }
        await().atMost(Duration.ofSeconds(10)).until { summaries.findByChatIdOrderByThroughTurnAsc(f.chat.id).count { it.throughTurn == 20 } == 2 }
        assertThat(summaries.findFirstByChatIdAndInvalidatedAtIsNullOrderByThroughTurnDesc(f.chat.id)?.throughTurn).isEqualTo(20)
    }
    private fun append(f: Fixture) {
        val chat = current(f)
        persister.persistTurn(chat.id, "다음", "다음 응답", emptyList(), judgmentSource = TurnJudgmentSource(),
            expectedState = GenerationStartState(visible(f).last { it.role == MessageRole.ASSISTANT }.id, chat.revision), billingModelCode = "LITE")
    }
    @Test fun `숨긴 도달 턴만 엔딩을 되돌리고 회원 집계는 보존한다`() {
        val user = member()
        val f = fixture(5, user = user, endingAt = 5)
        reachRecorder.record(user.id, f.chat.storyId, null, "기존 엔딩")
        edit(f, user = user)
        assertThat(current(f).status).isEqualTo(ChatStatus.ACTIVE)
        assertThat(current(f).reachedEndingNameSnapshot).isNull()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_story_ending_reaches WHERE user_id=? AND story_id=?", Int::class.java, user.id, f.chat.storyId)).isEqualTo(1)
        val after = fixture(7, endingAt = 5)
        edit(after, 7)
        assertThat(current(after).status).isEqualTo(ChatStatus.ENDED)
        assertThat(current(after).reachedEndingNameSnapshot).isEqualTo("기존 엔딩")
    }
    @Test fun `숨긴 턴과 다른 채팅 턴은 없고 잘못된 입력은 400이다`() {
        val f = fixture(); val other = fixture()
        edit(f)
        for (id in listOf(f.turns[2].id, other.turns[0].id)) {
            http.post().uri("/api/v1/chats/${f.chat.publicId}/turns/edit/stream").contentType(MediaType.APPLICATION_JSON)
                .body("""{"turnId":$id,"userInput":"입력","realtimeImage":false}""").exchange().expectStatus().isNotFound
        }
        for (body in listOf("""{"turnId":0,"userInput":"입력"}""", """{"turnId":1,"userInput":" "}""", """{"userInput":"입력"}""", """{"turnId":1,"userInput":"${"가".repeat(3001)}"}""", """{"turnId":1,"userInput":"입력","userSource":"invalid"}""")) {
            http.post().uri("/api/v1/chats/${f.chat.publicId}/turns/edit/stream").contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isBadRequest
        }
    }
    @Test fun `회원 유료 수정은 새 턴만 과금하고 숨긴 턴 차감은 대사에서도 환불하지 않는다`() {
        val user = member(); wallet.reward(user.id, 1000, CreditReason.SIGNUP_REWARD, "edit-seed")
        val f = fixture(user = user, model = "AURORA")
        wallet.deduct(user.id, 50, CreditReason.CHAT_TURN, refType = "CHAT_TURN", refId = f.chat.id)
        val charge = transactions.findAll().single { it.reason == CreditReason.CHAT_TURN }
        val target = messages.findById(f.turns[2].id).orElseThrow(); target.creditTransactionId = charge.id; messages.saveAndFlush(target)
        edit(f, user = user)
        assertThat(wallet.balanceOf(user.id)).isEqualTo(900)
        assertThat(messages.findById(target.id).orElseThrow().creditTransactionId).isEqualTo(charge.id)
        jdbc.update("UPDATE credit_transactions SET created_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), charge.id)
        reconciliation.reconcile()
        assertThat(transactions.findAll().filter { it.reason == CreditReason.REFUND }).isEmpty()
        assertThat(transactions.findLegacyChatCompletedCount(user.id, f.chat.id)).isEqualTo(4)
    }
    @Test fun `라이트 회원 수정은 체험을 쓰고 게스트도 체험을 한 번 쓴다`() {
        val user = member(); val f = fixture(user = user)
        edit(f, user = user)
        assertThat(trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isEqualTo(1)
        assertThat(wallet.balanceOf(user.id)).isZero()
        val guest = fixture(); edit(guest)
        assertThat(trials.usage(null, "edit-${guest.chat.id}", GuestTrialLimitService.Counter.CHAT_TURN).used).isEqualTo(1)
    }
    @Test fun `이프 부족과 게스트 한도 소진은 수정과 숨김을 시작하지 않는다`() {
        val user = member(); val paid = fixture(user = user, model = "AURORA")
        http.post().uri("/api/v1/chats/${paid.chat.publicId}/turns/edit/stream").header("Authorization", "Bearer ${token(user)}")
            .contentType(MediaType.APPLICATION_JSON).body("""{"turnId":${paid.turns[0].id},"userInput":"입력","realtimeImage":false}""")
            .exchange().expectStatus().isEqualTo(402)
        assertClean(paid)
        val guest = fixture(); val device = "edit-${guest.chat.id}"
        val limit = trials.usage(null, device, GuestTrialLimitService.Counter.CHAT_TURN).limit!!
        repeat(limit.toInt()) { trials.reserveForGuestOrNull(null, device, GuestTrialLimitService.Counter.CHAT_TURN) }
        http.post().uri("/api/v1/chats/${guest.chat.publicId}/turns/edit/stream").header("X-Manyak-Device-Id", device)
            .contentType(MediaType.APPLICATION_JSON).body("""{"turnId":${guest.turns[0].id},"userInput":"입력","realtimeImage":false}""")
            .exchange().expectStatus().isEqualTo(402)
        assertClean(guest)
    }
    @Test fun `수정 키는 EDIT를 노출하고 이어쓰기 재생성 수정의 중복 실행을 거절한다`() {
        val f = fixture()
        ai.beforeResult = {
            val state = service.getChatDetail(f.chat.publicId.toString(), null).generatingTurn!!
            assertThat(state.type).isEqualTo("EDIT"); assertThat(state.targetTurnId).isEqualTo(f.turns[2].id)
            assertThatThrownBy { service.streamChatTurn(f.chat.publicId.toString(), ContinueChatRequest("다른 입력"), deviceId = "different") }.hasMessageContaining("409")
            assertThatThrownBy { service.regenerateChatTurn(f.chat.publicId.toString(), RegenerateChatRequest(f.turns.last().id), deviceId = "different") }.hasMessageContaining("409")
            assertThatThrownBy { service.editChatTurn(f.chat.publicId.toString(), EditChatRequest(f.turns[2].id, "다시"), deviceId = "different") }.hasMessageContaining("409")
        }
        assertThat(edit(f)).contains("completed")
    }
    @Test fun `생성 중 개정이나 마지막 턴 변경은 숨김 없이 폐기하고 유료 수정분을 환불한다`() {
        for (revisionOnly in listOf(true, false)) {
            val user = member(); wallet.reward(user.id, 100, CreditReason.SIGNUP_REWARD, "edit-${user.id}")
            val f = fixture(user = user, model = "AURORA")
            ai.beforeResult = {
                if (revisionOnly) jdbc.update("UPDATE story_chats SET revision=revision+1 WHERE id=?", f.chat.id)
                else append(f)
            }
            assertThat(edit(f, user = user)).contains("CHAT_TURN_STALE").doesNotContain("event:completed")
            assertThat(messages.findAll().filter { it.chatId == f.chat.id && it.hiddenAt != null }).isEmpty()
            await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(wallet.balanceOf(user.id)).isEqualTo(100) }
        }
    }
    @Test fun `수정 후 상세 카드 공유 선택지와 요약 원문은 숨긴 턴을 제외한다`() {
        val f = fixture()
        val share = service.createChatShare(f.chat.publicId.toString(), null)
        edit(f)
        val detail = service.getChatDetail(f.chat.publicId.toString(), null)
        assertThat(detail.turns.map { it.id }).containsExactly(f.turns[0].id, f.turns[1].id, visible(f).last().id)
        assertThat(service.getChatShare(share.shareId, null).turns.map { it.aiOutput }).doesNotContain("a3", "a4", "a5")
        assertThat(service.getChatsByIds(BatchChatRequest(listOf(f.chat.publicId.toString())), null).single().lastStoryPreview).contains("수정 응답")
        val latest = messages.findLatestMessagesByChatIdsAndRole(listOf(f.chat.id), MessageRole.ASSISTANT).single()
        assertThat(latest.content).isEqualTo("수정 응답")
        service.generateChoices(f.chat.publicId.toString(), latest.id)
        assertThat(ai.requests.last().history.map { it.content }).containsExactly("u1", "a1", "u2", "a2")
        compactService.compact(ChatTurnCommitted(f.chat.id, f.chat.publicId, f.chat.storyId, 3))
        assertThat(compact.requests.last().history.map { it.content }).doesNotContain("a3", "a4", "a5", "s3")
    }
    @Test fun `수정 저장 롤백은 숨김 엔딩 요약 개정 횟수와 새 턴을 함께 되돌린다`() {
        val f = fixture(5, endingAt = 5)
        summaries.save(StoryChatSummary(chatId = f.chat.id, throughTurn = 5, summary = "기존 요약"))
        org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult { status ->
            persister.persistTurn(f.chat.id, "수정", "응답", emptyList(), judgmentSource = TurnJudgmentSource(),
                expectedState = GenerationStartState(f.turns.last().id, 0), billingModelCode = "LITE", editTurnId = f.turns[2].id)
            status.setRollbackOnly()
        }
        assertClean(f)
        assertThat(current(f).status).isEqualTo(ChatStatus.ENDED)
        assertThat(current(f).currentTurn).isEqualTo(5)
        assertThat(current(f).hiddenTurnCount).isZero()
        assertThat(visible(f)).hasSize(15)
        assertThat(summaries.findByChatIdOrderByThroughTurnAsc(f.chat.id).single().invalidatedAt).isNull()
        assertThat(compact.requests).isEmpty()
    }

    @Test fun `입력 수정의 접근 권한과 게스트 헤더를 검증한다`() {
        val user = member(); val f = fixture(user = user)
        val other = member()
        for (auth in listOf<String?>(null, token(other))) {
            val request = http.post().uri("/api/v1/chats/${f.chat.publicId}/turns/edit/stream").contentType(MediaType.APPLICATION_JSON)
            if (auth != null) request.header("Authorization", "Bearer $auth")
            request.body("""{"turnId":${f.turns[0].id},"userInput":"입력","realtimeImage":false}""").exchange().expectStatus().isForbidden
        }
        user.status = UserStatus.SUSPENDED; users.saveAndFlush(user)
        http.post().uri("/api/v1/chats/${f.chat.publicId}/turns/edit/stream").header("Authorization", "Bearer ${token(user)}")
            .contentType(MediaType.APPLICATION_JSON).body("""{"turnId":${f.turns[0].id},"userInput":"입력","realtimeImage":false}""")
            .exchange().expectStatus().isForbidden
        val guest = fixture()
        http.post().uri("/api/v1/chats/${guest.chat.publicId}/turns/edit/stream").contentType(MediaType.APPLICATION_JSON)
            .body("""{"turnId":${guest.turns[0].id},"userInput":"입력","realtimeImage":false}""").exchange().expectStatus().isBadRequest
        assertClean(f); assertClean(guest)
    }
    @Test fun `AI 실패는 숨김과 요약 변경 없이 게스트 체험을 복원한다`() {
        val f = fixture()
        summaries.save(StoryChatSummary(chatId = f.chat.id, throughTurn = 5, summary = "기존 요약"))
        ai.beforeResult = { throw IllegalStateException("AI unavailable") }
        assertThat(edit(f)).contains("AI_STREAM_FAILED")
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(trials.usage(null, "edit-${f.chat.id}", GuestTrialLimitService.Counter.CHAT_TURN).used).isZero()
        }
        assertClean(f)
        assertThat(summaries.findByChatIdOrderByThroughTurnAsc(f.chat.id).single().invalidatedAt).isNull()
    }
    @Test fun `입력 수정으로 저장한 10번째 턴도 커밋 뒤 요약을 시작한다`() {
        val f = fixture(12)
        assertThat(edit(f, 10)).contains("completed")
        await().atMost(Duration.ofSeconds(10)).until { summaries.findFirstByChatIdAndInvalidatedAtIsNullOrderByThroughTurnDesc(f.chat.id)?.throughTurn == 10 }
        assertThat(compact.requests.single().history.map { it.content }).doesNotContain("a10", "a11", "a12")
    }

    @Test fun `수정은 완료 턴 수가 간격 배수일 때만 요약을 시작한다`() {
        val f = fixture(12)
        edit(f, 9)
        assertThat(compact.requests).isEmpty()
        append(f)
        await().atMost(Duration.ofSeconds(10)).until { summaries.findFirstByChatIdAndInvalidatedAtIsNullOrderByThroughTurnDesc(f.chat.id)?.throughTurn == 10 }
        assertThat(compact.requests.single().history.map { it.content }).doesNotContain("a9", "a10", "a11", "a12")
    }
}

class ChatInputEditTestConfig {
    fun editAi() = ChatInputEditIntegrationTests.Ai()
    fun editCompact() = ChatInputEditIntegrationTests.Compact()
}

private val scriptedChatInputEditTestConfigEditAi = ChatInputEditTestConfig().editAi()
private val scriptedChatInputEditTestConfigEditCompact = ChatInputEditTestConfig().editCompact()
