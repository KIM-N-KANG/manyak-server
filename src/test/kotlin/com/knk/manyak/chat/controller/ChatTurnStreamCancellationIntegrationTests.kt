package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.client.*
import com.knk.manyak.chat.dto.RegenerateChatRequest
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.service.ChatService
import com.knk.manyak.chat.service.ChatRealtimeImageService
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.*
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.mockito.Mockito
import java.util.concurrent.Executor
import java.util.function.Consumer

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(ChatTurnStreamCancellationTestConfig::class)
@ActiveProfiles("test")
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:manyak-stream-cancel;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
])
class ChatTurnStreamCancellationIntegrationTests {
    @Autowired private lateinit var chatModelService: com.knk.manyak.chat.service.ChatModelService
    @Autowired private lateinit var chatModelRepository: com.knk.manyak.chat.repository.ChatModelRepository

    class ControlledWorker : Executor {
        lateinit var task: Runnable
        override fun execute(command: Runnable) { task = command }
    }

    class ControlledAi : ChatTurnAiClient {
        var delegate: ChatTurnAiClient? = null
        var beforeCompleted: () -> Unit = {}
        var tokenAfterClosure = false
        var characterImageAfterCancellation = false
        var returnedCompleted = false
        override fun streamTurn(
            request: ChatTurnAiRequest, traceLink: AiTraceLink,
            deadline: java.time.Instant?,
            onCharacterImage: (ChatCharacterImageEvent) -> Unit, onToken: (String) -> Unit,
        ): ChatTurnAiResult {
            delegate?.let { return it.streamTurn(request, traceLink, deadline, onCharacterImage, onToken) }
            beforeCompleted()
            if (characterImageAfterCancellation) onCharacterImage(ChatCharacterImageEvent("인물", "https://cdn.test/character.webp"))
            if (tokenAfterClosure) onToken("진행 토큰")
            returnedCompleted = true
            // 종료 뒤 token 없이 ping·completed만 오는 상황: send 실패에 의존하면 저장을 막지 못한다.
            return ChatTurnAiResult("늦게 도착한 완료 결과", emptyList())
        }
        override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String, traceLink: AiTraceLink) =
            ChatChoicesResult(emptyList())
    }

    @Autowired lateinit var mockMvc: org.springframework.test.web.servlet.MockMvc
    @Autowired lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired lateinit var service: ChatService
    private val worker = ControlledWorker()
    @MockitoBean(name = "chatSseExecutor") lateinit var executor: Executor
    @Autowired lateinit var ai: ControlledAi
    @Autowired lateinit var stories: StoryRepository
    @MockitoSpyBean lateinit var chats: StoryChatRepository
    @Autowired lateinit var messages: StoryMessageRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var wallet: CreditWalletService
    @Autowired lateinit var transactions: CreditTransactionRepository
    @Autowired lateinit var policies: com.knk.manyak.credit.repository.CreditPolicyRepository
    @Autowired lateinit var policy: CreditPolicyService
    @Autowired lateinit var trials: GuestTrialLimitService
    @Autowired lateinit var meters: io.micrometer.core.instrument.MeterRegistry
    @Autowired lateinit var cleaner: DatabaseCleaner
    @MockitoSpyBean lateinit var images: ChatRealtimeImageService

    @BeforeEach fun setUp() {
        Mockito.doAnswer { call ->
            worker.execute(call.getArgument(0))
            null
        }.`when`(executor).execute(Mockito.any(Runnable::class.java))
        Mockito.doAnswer { call ->
            deadline = call.getArgument(0)
            Mockito.mock(java.util.concurrent.ScheduledFuture::class.java)
        }.`when`(chatGenerationDeadlines).schedule(Mockito.any(Runnable::class.java) ?: Runnable {}, Mockito.anyLong())
        cleaner.cleanAll()
        policy.refresh()
        ai.delegate = null
        ai.beforeCompleted = {}
        ai.tokenAfterClosure = false
        ai.characterImageAfterCancellation = false
        ai.returnedCompleted = false
    }

    @MockitoBean lateinit var chatGenerationDeadlines: com.knk.manyak.chat.service.ChatGenerationDeadlines
    @MockitoSpyBean lateinit var store: com.knk.manyak.chat.service.ChatGenerationStore
    @MockitoSpyBean lateinit var redis: org.springframework.data.redis.core.StringRedisTemplate
    @MockitoSpyBean lateinit var logger: com.knk.manyak.global.observability.StructuredLogger
    private lateinit var deadline: Runnable

    private fun deadline() = deadline.run()
    private fun input() = ContinueChatRequest("진행", realtimeImage = false)
    private fun start(member: User, chat: StoryChat) = service.streamChatTurn(chat.publicId.toString(), input(), member.id)
    private fun state(chat: StoryChat, member: User) = service.getChatDetail(chat.publicId.toString(), member.id).generatingTurn
    private fun refunds() = transactions.findAll().filter { it.reason == CreditReason.REFUND }
    private fun rejectedCount() = meters.find("manyak.chat.turn.result").tag("outcome", "rejected").counter()?.count() ?: 0.0
    private fun successCount() = meters.find("manyak.chat.turn.result").tag("outcome", "success").counter()?.count() ?: 0.0
    private fun assertCode(code: String, action: () -> Unit) {
        org.assertj.core.api.Assertions.assertThatThrownBy { action() }
            .isInstanceOfSatisfying(com.knk.manyak.global.error.CodedResponseStatusException::class.java) {
                assertThat(it.errorCode).isEqualTo(code)
            }
    }
    private fun seedTurn(chat: StoryChat): StoryMessage {
        chat.currentTurn = 1
        chats.save(chat)
        messages.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "기존 입력", messageOrder = 1))
        return messages.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "기존 본문", messageOrder = 2))
    }

    @Test fun `RUNNING 연결 종료와 오류 및 전송 실패 뒤에도 생성 저장 과금과 success를 유지한다`() {
        listOf("completionCallback", "errorCallback", "timeoutCallback").forEach { callback ->
            val (member, chat, _) = paidChat()
            val beforeSuccess = successCount()
            val beforeCancelled = cancelledCount()
            val emitter = start(member, chat)
            ai.beforeCompleted = { terminate(emitter, callback); emitter.complete() }
            ai.tokenAfterClosure = true
            ai.characterImageAfterCancellation = true
            worker.task.run()
            assertThat(ai.returnedCompleted).isTrue()
            assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(1)
            assertThat(wallet.balanceOf(member.id)).isZero()
            assertThat(refunds()).isEmpty()
            assertThat(state(chat, member)).isNull()
            assertThat(successCount()).isEqualTo(beforeSuccess + 1)
            assertThat(cancelledCount()).isEqualTo(beforeCancelled)
        }
    }

    @Test fun `PENDING 연결 종료 뒤에도 작업이 실행되고 저장한다`() {
        val (member, chat, _) = paidChat()
        val emitter = start(member, chat)
        emitter.complete()
        terminate(emitter, "completionCallback")
        assertThat(state(chat, member)).isNotNull()
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(1)
        assertThat(wallet.balanceOf(member.id)).isZero()
        assertThat(refunds()).isEmpty()
    }

    @Test fun `저장 진입 뒤 연결 종료와 생성 마감은 커밋과 success 및 과금을 유지한다`() {
        val (member, chat, _) = paidChat()
        val emitter = start(member, chat)
        val before = successCount()
        Mockito.doAnswer { call ->
            terminate(emitter, "completionCallback")
            deadline()
            call.callRealMethod()
        }.`when`(images).validate(
            Mockito.any(ChatRealtimeImageService.Reservation::class.java) ?: ChatRealtimeImageService.Reservation(null),
            Mockito.any(ChatTurnAiResult::class.java) ?: ChatTurnAiResult("", emptyList()),
        )
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(1)
        assertThat(wallet.balanceOf(member.id)).isZero()
        assertThat(refunds()).isEmpty()
        assertThat(successCount()).isEqualTo(before + 1)
        Mockito.verify(logger).event("chat_turn_timeout", "chat_id" to chat.publicId.toString(), "worker_state" to "SAVING")
    }

    @Test fun `RUNNING 생성 마감 선점은 늦은 완료를 폐기하고 failure 및 환불을 한 번 기록한다`() {
        val (member, chat, cost) = paidChat()
        start(member, chat)
        val before = failureCount()
        ai.beforeCompleted = { deadline(); deadline() }
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).isEmpty()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(refunds()).hasSize(1)
        assertThat(failureCount()).isEqualTo(before + 1)
        assertThat(state(chat, member)).isNull()
        Mockito.verify(logger).event("chat_turn_timeout", "chat_id" to chat.publicId.toString(), "worker_state" to "RUNNING")
        Mockito.verify(logger, Mockito.never()).event("chat_turn_timeout", "chat_id" to chat.publicId.toString(), "worker_state" to "CANCELLED")
    }

    @Test fun `PENDING 생성 마감은 큐 작업 실행 없이 환불하고 키를 해제한다`() {
        val (member, chat, cost) = paidChat()
        start(member, chat)
        val before = failureCount()
        ai.beforeCompleted = { error("마감된 작업은 AI를 호출하지 않는다") }
        deadline(); deadline()
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(refunds()).hasSize(1)
        assertThat(failureCount()).isEqualTo(before + 1)
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `생성 마감은 회원 턴 체험을 한 번 복원한다`() {
        val member = users.save(User(nickname = "체험", status = UserStatus.ACTIVE))
        while (trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)) { }
        trials.restoreMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)
        val chat = newChat(member.id)
        start(member, chat)
        ai.beforeCompleted = { deadline(); deadline() }
        worker.task.run()
        assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()
        assertThat(transactions.findAll()).isEmpty()
    }

    @Test fun `게스트 생성 마감은 마지막 턴 체험을 한 번 복원한다`() {
        val device = "deadline-guest-${java.util.UUID.randomUUID()}"
        while (trials.reserve(device, GuestTrialLimitService.Counter.CHAT_TURN)) { }
        trials.restore(device, GuestTrialLimitService.Counter.CHAT_TURN)
        val chat = chats.save(StoryChat(storyId = stories.save(Story(title = "게스트", genre = "판타지")).id))
        service.streamChatTurn(chat.publicId.toString(), input(), deviceId = device)
        deadline(); deadline(); worker.task.run()
        assertThat(trials.reserve(device, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        assertThat(trials.reserve(device, GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()
    }

    @Test fun `생성 마감은 유료 턴과 이미지 차감을 각각 한 번 환불한다`() {
        val (member, chat, cost) = paidChat()
        wallet.reward(member.id, 7, CreditReason.SIGNUP_REWARD, "image-seed:${member.id}")
        Mockito.doReturn(ChatRealtimeImageService.Reservation(null, userId = member.id, cost = 7))
            .`when`(images).prepare(true, member.id, null)
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행"), member.id)
        ai.beforeCompleted = { deadline(); deadline() }
        worker.task.run()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost + 7)
        assertThat(refunds()).hasSize(2)
        assertThat(refunds().map { it.refType }).containsExactlyInAnyOrder("CHAT_TURN", "CHAT_IMAGE")
    }

    @Test fun `마감 뒤 이미지 콜백 중단과 복원은 한 번만 수행한다`() {
        val (member, chat, cost) = paidChat()
        while (trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)) { }
        trials.restoreMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)
        Mockito.doAnswer {
            assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)).isTrue()
            ChatRealtimeImageService.Reservation(null, userId = member.id, trialReserved = true)
        }.`when`(images).prepare(true, member.id, null)
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행"), member.id)
        ai.beforeCompleted = { deadline() }
        ai.characterImageAfterCancellation = true
        worker.task.run()
        assertThat(ai.returnedCompleted).isFalse()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)).isTrue()
        assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)).isFalse()
    }

    @Test fun `생성 중 상세와 started는 같은 UUID와 UTC 시각을 제공하고 종료 뒤 상태가 사라진다`() {
        val (member, chat, _) = paidChat()
        val emitter = start(member, chat)
        val generation = state(chat, member)!!
        assertThat(generation.type).isEqualTo("CONTINUE")
        assertThat(generation.userInput).isEqualTo("진행")
        assertThat(generation.targetTurnId).isNull()
        assertThat(java.util.UUID.fromString(generation.generationId)).isNotNull()
        assertThat(generation.startedAt.toString()).endsWith("Z")
        worker.task.run()
        assertThat(state(chat, member)).isNull()
        assertThat(streamRecords(chat, generation.generationId)).contains(generation.generationId)
    }

    @Test fun `재생성 상태와 연결 이탈 뒤 활성본 교체를 확인한다`() {
        val (member, chat, _) = paidChat()
        val turn = seedTurn(chat)
        val emitter = service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, false), member.id)
        val generation = state(chat, member)!!
        assertThat(generation.type).isEqualTo("REGENERATE")
        assertThat(generation.targetTurnId).isEqualTo(turn.id)
        assertThat(generation.userInput).isEqualTo("기존 입력")
        emitter.complete()
        worker.task.run()
        assertThat(messages.findById(turn.id).orElseThrow().content).isEqualTo("늦게 도착한 완료 결과")
        assertThat(chats.findById(chat.id).orElseThrow().regeneratedCount).isEqualTo(1)
        assertThat(refunds()).isEmpty()
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `이어쓰기와 재생성은 같은 생성 키를 사용해 재전송과 추가 과금을 막는다`() {
        val (member, chat, _) = paidChat()
        val turn = seedTurn(chat)
        start(member, chat)
        val before = rejectedCount()
        assertCode("CHAT_TURN_IN_PROGRESS") { start(member, chat) }
        assertCode("CHAT_TURN_IN_PROGRESS") { service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, false), member.id) }
        assertThat(transactions.findAll().filter { it.reason == CreditReason.CHAT_TURN }).hasSize(1)
        assertThat(rejectedCount()).isEqualTo(before + 2)
        worker.task.run()
    }

    @Test fun `재생성 중 이어쓰기와 다른 재생성은 거절한다`() {
        val (member, chat, _) = paidChat()
        val turn = seedTurn(chat)
        service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, false), member.id)
        assertCode("CHAT_TURN_IN_PROGRESS") { start(member, chat) }
        assertCode("CHAT_TURN_IN_PROGRESS") { service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, false), member.id) }
        worker.task.run()
        assertThat(refunds()).isEmpty()
    }

    @Test fun `낡은 마지막 턴과 명시적 null은 과금 전에 거절하고 획득 키를 해제한다`() {
        val (member, chat, _) = paidChat()
        seedTurn(chat)
        listOf<Long?>(null, -1).forEach { id ->
            val request = input().apply { lastTurnId = id }
            assertCode("CHAT_TURN_STALE") { service.streamChatTurn(chat.publicId.toString(), request, member.id) }
            assertThat(state(chat, member)).isNull()
        }
        assertThat(transactions.findAll().filter { it.reason == CreditReason.CHAT_TURN }).isEmpty()
        assertThat(ai.returnedCompleted).isFalse()
        start(member, chat) // 구버전 생략 허용
        worker.task.run()
    }

    @Test fun `첫 턴 명시적 null은 일치하고 정상 생성한다`() {
        val (member, chat, _) = paidChat()
        service.streamChatTurn(chat.publicId.toString(), input().apply { lastTurnId = null }, member.id)
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(1)
    }

    // 해제 시점의 실제 원장을 읽어, 키가 사라진 직후 재요청해도 환불 전 잔액을 보지 않게 한다.
    private fun observeBalanceAtRelease(member: User): MutableList<Long> {
        val balances = mutableListOf<Long>()
        Mockito.doAnswer { call ->
            balances.add(wallet.balanceOf(member.id))
            call.callRealMethod()
        }.`when`(store).release(Mockito.anyString(), Mockito.anyString())
        return balances
    }

    @Test fun `AI 실패는 환불을 반영한 뒤 생성 키를 한번 해제한다`() {
        val (member, chat, cost) = paidChat()
        val balances = observeBalanceAtRelease(member)
        val before = failureCount()
        ai.beforeCompleted = { throw ChatTurnAiException("AI_TIMEOUT", "timeout") }
        start(member, chat)
        worker.task.run()
        assertThat(balances).containsExactly(cost)
        assertThat(refunds()).hasSize(1)
        assertThat(failureCount()).isEqualTo(before + 1)
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `실행기 거부는 환불을 반영한 뒤 생성 키를 한번 해제한다`() {
        val (member, chat, cost) = paidChat()
        val balances = observeBalanceAtRelease(member)
        val before = failureCount()
        Mockito.doThrow(java.util.concurrent.RejectedExecutionException("full"))
            .`when`(executor).execute(Mockito.any(Runnable::class.java))
        org.assertj.core.api.Assertions.assertThatThrownBy { start(member, chat) }
            .isInstanceOf(java.util.concurrent.RejectedExecutionException::class.java)
        assertThat(balances).containsExactly(cost)
        assertThat(refunds()).hasSize(1)
        assertThat(failureCount()).isEqualTo(before + 1)
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `선차감 뒤 이미지 발급 예외는 환불을 반영한 뒤 생성 키를 한번 해제한다`() {
        val (member, chat, cost) = paidChat()
        wallet.reward(member.id, 7, CreditReason.SIGNUP_REWARD, "image-seed:${member.id}")
        Mockito.doReturn(ChatRealtimeImageService.Reservation(null, userId = member.id, cost = 7))
            .`when`(images).prepare(true, member.id, null)
        val balances = observeBalanceAtRelease(member)
        val before = failureCount()
        Mockito.doThrow(IllegalStateException("issue failed")).`when`(images).issue(
            Mockito.any(ChatRealtimeImageService.Reservation::class.java) ?: ChatRealtimeImageService.Reservation(null),
            Mockito.eq(chat.publicId) ?: chat.publicId, Mockito.anyInt(),
        )
        org.assertj.core.api.Assertions.assertThatThrownBy {
            service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행"), member.id)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(balances).containsExactly(cost + 7)
        assertThat(refunds()).hasSize(2)
        assertThat(refunds().map { it.refType }).containsExactlyInAnyOrder("CHAT_TURN", "CHAT_IMAGE")
        assertThat(failureCount()).isEqualTo(before + 1)
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `이미지 복원 실패를 기록해도 턴 환불 뒤 생성 키를 한번 해제한다`() {
        val (member, chat, cost) = paidChat()
        val balances = observeBalanceAtRelease(member)
        ai.beforeCompleted = { throw ChatTurnAiException("AI_TIMEOUT", "timeout") }
        Mockito.doThrow(IllegalStateException("restore failed")).`when`(images).restore(
            Mockito.any(ChatRealtimeImageService.Reservation::class.java) ?: ChatRealtimeImageService.Reservation(null),
        )
        start(member, chat)
        worker.task.run()
        assertThat(balances).containsExactly(cost)
        assertThat(refunds()).hasSize(1)
        Mockito.verify(logger).event("chat_image_refund_failed", "chat_pk" to chat.id, "error" to "IllegalStateException")
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `잔액 부족과 실행기 거부는 생성 키를 해제하고 거부 차감은 환불한다`() {
        val (member, chat, cost) = paidChat()
        wallet.deduct(member.id, cost, CreditReason.CHAT_TURN, "empty:${member.id}")
        org.assertj.core.api.Assertions.assertThatThrownBy { start(member, chat) }.isInstanceOf(com.knk.manyak.credit.InsufficientCreditException::class.java)
        assertThat(state(chat, member)).isNull()
        wallet.reward(member.id, cost, CreditReason.SIGNUP_REWARD, "retry:${member.id}")
        Mockito.doThrow(java.util.concurrent.RejectedExecutionException("full")).`when`(executor).execute(Mockito.any(Runnable::class.java))
        org.assertj.core.api.Assertions.assertThatThrownBy { start(member, chat) }.isInstanceOf(java.util.concurrent.RejectedExecutionException::class.java)
        assertThat(state(chat, member)).isNull()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(refunds()).hasSize(1)
    }

    @Test fun `Redis 획득 오류는 503 failure이며 과금과 AI 호출이 없다`() {
        val (member, chat, cost) = paidChat()
        val before = failureCount()
        Mockito.doThrow(com.knk.manyak.global.error.CodedResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Redis down"))
            .`when`(store).acquire(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(java.time.Instant::class.java) ?: java.time.Instant.now(), Mockito.isNull())
        assertCode("SERVICE_UNAVAILABLE") { start(member, chat) }
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(ai.returnedCompleted).isFalse()
        assertThat(failureCount()).isEqualTo(before + 1)
    }

    @Test fun `키가 없어 같은 시작 상태의 이어쓰기 둘이 완료해도 한 결과만 저장하고 나머지는 환불한다`() {
        val (member, chat, cost) = paidChat()
        wallet.reward(member.id, cost, CreditReason.SIGNUP_REWARD, "second:${member.id}")
        start(member, chat)
        val first = worker.task
        redis.delete("chat:${chat.publicId}:generating")
        start(member, chat)
        val secondId = state(chat, member)!!.generationId
        first.run()
        assertThat(state(chat, member)).isNotNull() // 이전 작업이 새 키를 지우지 않음
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(1)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)
        assertThat(refunds()).hasSize(1)
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(streamRecords(chat, secondId)).contains("CHAT_TURN_STALE")
    }

    @Test fun `같은 턴 ID 재생성 교체도 시작 상태 불일치로 폐기한다`() {
        val (member, chat, cost) = paidChat()
        val turn = seedTurn(chat)
        wallet.reward(member.id, cost, CreditReason.SIGNUP_REWARD, "second:${member.id}")
        service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, false), member.id)
        val first = worker.task
        redis.delete("chat:${chat.publicId}:generating")
        service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, false), member.id)
        first.run(); worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().regeneratedCount).isEqualTo(1)
        assertThat(refunds()).hasSize(1)
    }

    @Test fun `이탈 후 AI 실패는 저장 없이 환불하고 키를 해제한다`() {
        val (member, chat, cost) = paidChat()
        start(member, chat).complete()
        ai.beforeCompleted = { throw ChatTurnAiException("AI_FAILED", "failed") }
        worker.task.run()
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).isEmpty()
        assertThat(refunds()).hasSize(1)
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `상세는 Redis 읽기 오류에도 200과 대화 기록을 반환하고 생성 상태만 null로 내리며 로그를 남긴다`() {
        val chat = chats.save(StoryChat(storyId = stories.save(Story(title = "상세 읽기", genre = "판타지")).id))
        seedTurn(chat)
        @Suppress("UNCHECKED_CAST")
        val values = Mockito.mock(org.springframework.data.redis.core.ValueOperations::class.java)
            as org.springframework.data.redis.core.ValueOperations<String, String>
        Mockito.`when`(values.get("chat:${chat.publicId}:generating"))
            .thenThrow(org.springframework.data.redis.RedisConnectionFailureException("redis down"))
        Mockito.doReturn(values).`when`(redis).opsForValue()

        assertDetailReadFailure(chat, "RedisConnectionFailureException")
    }

    @Test fun `상세는 첫 DB 조회 전에 Redis 생성 상태를 읽는다`() {
        val chat = chats.save(StoryChat(storyId = stories.save(Story(title = "조회 순서", genre = "판타지")).id))
        seedTurn(chat)
        Mockito.clearInvocations(store, chats)

        val detail = service.getChatDetail(chat.publicId.toString(), null)

        assertThat(detail.turns).hasSize(1)
        val order = Mockito.inOrder(store, chats)
        order.verify(store).get(chat.publicId.toString())
        order.verify(chats).findByPublicIdAndDeletedAtIsNull(chat.publicId)
    }

    @Test fun `상세는 잘못된 UUID를 Redis 조회 없이 404로 거절한다`() {
        Mockito.clearInvocations(store, chats)

        val response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/v1/chats/not-a-uuid")).andReturn().response

        assertThat(response.status).isEqualTo(404)
        Mockito.verify(store, Mockito.never()).get(Mockito.anyString())
        Mockito.verifyNoInteractions(chats)
    }

    @Test fun `상세는 생성 상태 JSON 해석 실패에도 200과 대화 기록을 반환하고 생성 상태만 null로 내리며 로그를 남긴다`() {
        val chat = chats.save(StoryChat(storyId = stories.save(Story(title = "상세 읽기", genre = "판타지")).id))
        seedTurn(chat)
        val key = "chat:${chat.publicId}:generating"
        val malformed = "{invalid-json"
        redis.opsForValue().set(key, malformed)
        try {
            val parseError = org.junit.jupiter.api.assertThrows<tools.jackson.core.JacksonException> {
                mapper.readValue(malformed, com.knk.manyak.chat.dto.GeneratingTurnResponse::class.java)
            }
            assertDetailReadFailure(chat, parseError.javaClass.simpleName)
        } finally {
            redis.delete(key)
        }
    }

    private fun assertDetailReadFailure(chat: StoryChat, error: String) {
        val response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/v1/chats/${chat.publicId}")).andReturn().response
        assertThat(response.status).isEqualTo(200)
        val body = mapper.readTree(response.contentAsString)
        assertThat(body["generatingTurn"].isNull).isTrue()
        assertThat(body["turns"].size()).isEqualTo(1)
        assertThat(body["turns"][0]["userInput"].asString()).isEqualTo("기존 입력")
        assertThat(body["turns"][0]["aiOutput"].asString()).isEqualTo("기존 본문")
        Mockito.verify(logger).event("chat_generation_read_failed", mapOf("chat_id" to chat.publicId.toString(), "error" to error))
    }

    @Test fun `HTTP 계약은 명시적 null의 낡은 턴과 생성 중 충돌을 JSON으로 반환하고 상세와 SSE 생성 ID를 제공한다`() {
        val chat = chats.save(StoryChat(storyId = stories.save(Story(title = "HTTP", genre = "판타지")).id))
        val turn = seedTurn(chat)
        fun post(body: String) = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("X-Manyak-Device-Id", "http-generation-test")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
        val stale = post("""{"userInput":"진행","lastTurnId":null,"realtimeImage":false}""").andReturn()
        assertThat(stale.response.status).isEqualTo(409)
        assertThat(mapper.readTree(stale.response.contentAsString)["code"].asString()).isEqualTo("CHAT_TURN_STALE")
        val started = post("""{"userInput":"진행","lastTurnId":${turn.id},"realtimeImage":false}""").andReturn()
        assertThat(started.request.isAsyncStarted).isTrue()
        val inProgress = post("""{"userInput":"진행"}""").andReturn()
        assertThat(inProgress.response.status).isEqualTo(409)
        assertThat(mapper.readTree(inProgress.response.contentAsString)["code"].asString()).isEqualTo("CHAT_TURN_IN_PROGRESS")
        val detail = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/chats/${chat.publicId}")).andReturn()
        val generation = mapper.readTree(detail.response.contentAsString)["generatingTurn"]
        assertThat(generation["type"].asString()).isEqualTo("CONTINUE")
        assertThat(generation["targetTurnId"].isNull).isTrue()
        val id = generation["generationId"].asString()
        java.util.UUID.fromString(id)
        assertThat(generation["startedAt"].asString()).endsWith("Z")
        worker.task.run()
        val response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started)).andReturn().response
        assertThat(response.contentAsString).contains("event:started", id, "event:completed")
        val done = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/chats/${chat.publicId}")).andReturn()
        assertThat(mapper.readTree(done.response.contentAsString)["generatingTurn"].isNull).isTrue()
    }

    @Test fun `선택지는 생성 키 없이 실행하고 낡은 대상은 CHAT_TURN_STALE 엔딩 재생성은 CHAT_ENDED다`() {
        val (member, chat, _) = paidChat()
        val turn = seedTurn(chat)
        service.generateChoices(chat.publicId.toString(), turn.id, member.id)
        assertThat(state(chat, member)).isNull()
        Mockito.verify(store, Mockito.never()).acquire(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(java.time.Instant::class.java) ?: java.time.Instant.now(), Mockito.any())
        assertCode("CHAT_TURN_STALE") { service.generateChoices(chat.publicId.toString(), turn.id + 1, member.id) }
        chat.status = com.knk.manyak.chat.entity.ChatStatus.ENDED
        chats.save(chat)
        val before = rejectedCount()
        assertCode("CHAT_ENDED") { service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, false), member.id) }
        assertThat(rejectedCount()).isEqualTo(before + 1)
        assertThat(transactions.findAll().filter { it.reason == CreditReason.CHAT_TURN }).isEmpty()
    }

    @Test fun `요청 접수부터 남은 마감을 예약하고 연결 종료가 타이머를 재설정하지 않는다`() {
        val (member, chat, _) = paidChat()
        val emitter = start(member, chat)
        terminate(emitter, "completionCallback")
        terminate(emitter, "timeoutCallback")
        val delay = org.mockito.ArgumentCaptor.forClass(Long::class.javaObjectType)
        Mockito.verify(chatGenerationDeadlines, Mockito.times(1)).schedule(Mockito.any(Runnable::class.java) ?: Runnable {}, delay.capture())
        assertThat(delay.value).isBetween(1, ChatService.GENERATION_TIMEOUT_MILLIS)
        deadline()
        worker.task.run()
        Mockito.verify(logger).event("chat_turn_timeout", "chat_id" to chat.publicId.toString(), "worker_state" to "PENDING")
    }

    @Test fun `큐 마감과 실행기 거부가 겹쳐도 환불과 failure는 한 번이다`() {
        val (member, chat, cost) = paidChat()
        Mockito.doAnswer { deadline(); throw java.util.concurrent.RejectedExecutionException("full") }
            .`when`(executor).execute(Mockito.any(Runnable::class.java))
        val before = failureCount()
        org.assertj.core.api.Assertions.assertThatThrownBy { start(member, chat) }.isInstanceOf(java.util.concurrent.RejectedExecutionException::class.java)
        assertThat(failureCount()).isEqualTo(before + 1)
        assertThat(refunds()).hasSize(1)
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        Mockito.verify(store, Mockito.times(1)).release(Mockito.eq(chat.publicId.toString()) ?: "", Mockito.anyString())
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `예약 마감 뒤 워커의 경과 시간 재검사도 마감 로그를 중복 기록하지 않는다`() {
        val (member, chat, _) = paidChat()
        Mockito.doAnswer { call ->
            (call.callRealMethod() as com.knk.manyak.chat.dto.GeneratingTurnResponse).let { it.copy(startedAt = it.startedAt.minusSeconds(121)) }
        }.`when`(store).acquire(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(java.time.Instant::class.java) ?: java.time.Instant.now(), Mockito.isNull())
        start(member, chat)
        deadline()
        worker.task.run()
        deadline()
        Mockito.verify(logger, Mockito.times(1)).event("chat_turn_timeout", "chat_id" to chat.publicId.toString(), "worker_state" to "PENDING")
        Mockito.verify(logger, Mockito.never()).event("chat_turn_timeout", "chat_id" to chat.publicId.toString(), "worker_state" to "CANCELLED")
    }

    @Test fun `타이머 실행이 늦어도 접수 마감이 지난 큐 작업은 AI를 호출하지 않는다`() {
        val (member, chat, cost) = paidChat()
        Mockito.doAnswer { call ->
            (call.callRealMethod() as com.knk.manyak.chat.dto.GeneratingTurnResponse).let { it.copy(startedAt = it.startedAt.minusSeconds(121)) }
        }.`when`(store).acquire(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(java.time.Instant::class.java) ?: java.time.Instant.now(), Mockito.isNull())
        start(member, chat)
        ai.beforeCompleted = { error("접수 마감이 지난 작업은 호출하지 않는다") }
        worker.task.run()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(refunds()).hasSize(1)
        assertThat(state(chat, member)).isNull()
        assertThat(ai.returnedCompleted).isFalse()
    }

    @Test fun `이탈 중 재생성 마감은 기존 활성본과 턴 수를 유지하고 환불 및 키 해제한다`() {
        val (member, chat, cost) = paidChat()
        val turn = seedTurn(chat)
        service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, false), member.id).complete()
        ai.beforeCompleted = { deadline() }
        worker.task.run()
        assertThat(messages.findById(turn.id).orElseThrow().content).isEqualTo("기존 본문")
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(1)
        assertThat(chats.findById(chat.id).orElseThrow().regeneratedCount).isZero()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(refunds()).hasSize(1)
        assertThat(state(chat, member)).isNull()
    }

    @Test fun `ping 전용 AI 스트림은 스케줄러 마감 선점 뒤에도 워커를 반환하고 한번 정산한다`() {
        assertPingDeadline(schedulerFirst = true)
    }

    @Test fun `ping 전용 AI 스트림이 먼저 마감돼도 늦은 스케줄러와 한번만 정산한다`() {
        assertPingDeadline(schedulerFirst = false)
    }

    private fun assertPingDeadline(schedulerFirst: Boolean) {
        val (member, chat, cost) = paidChat()
        val ping = "event: ping\ndata: {}\n\n"
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(ping.repeat(50000)).throttleBody(8192, 20, java.util.concurrent.TimeUnit.MILLISECONDS))
        ai.delegate = RestChatTurnAiClient(
            org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(server.url("/").toString()).build(),
            mapper, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5),
        )
        Mockito.doAnswer { call ->
            (call.callRealMethod() as com.knk.manyak.chat.dto.GeneratingTurnResponse)
                .copy(startedAt = java.time.Instant.now().minusMillis(ChatService.GENERATION_TIMEOUT_MILLIS - 2000))
        }.`when`(store).acquire(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any(java.time.Instant::class.java) ?: java.time.Instant.now(), Mockito.isNull())
        val before = failureCount()
        val emitter = start(member, chat)
        val generationId = state(chat, member)!!.generationId
        val thread = Thread { worker.task.run() }
        try {
            thread.start()
            assertThat(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)).isNotNull()
            if (schedulerFirst) deadline()
            thread.join(4000)
            assertThat(thread.isAlive).describedAs("ping만 보내는 AI를 기다리지 않고 마감 직후 워커 종료").isFalse()
            deadline(); deadline()
            assertThat(failureCount()).isEqualTo(before + 1)
            assertThat(refunds()).hasSize(1)
            assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
            assertThat(state(chat, member)).isNull()
            assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).isEmpty()
            Mockito.verify(store, Mockito.times(1)).release(Mockito.eq(chat.publicId.toString()) ?: "", Mockito.anyString())
            Mockito.verify(logger, Mockito.times(1)).event("chat_turn_timeout", "chat_id" to chat.publicId.toString(), "worker_state" to "RUNNING")
            assertThat(streamRecords(chat, generationId).split("AI_STREAM_FAILED")).hasSize(2)
        } finally {
            thread.interrupt()
            thread.join(1000)
            server.shutdown()
        }
    }

    @MockitoSpyBean lateinit var generationEvents: com.knk.manyak.chat.service.ChatGenerationEvents

    @MockitoSpyBean lateinit var receivers: com.knk.manyak.chat.service.ChatGenerationReceivers

    private fun assertResumeResponse(chatId: String, generationId: String, status: Int, code: String) {
        val response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/v1/chats/$chatId/generations/$generationId/stream")
            .accept("text/event-stream", "application/json")).andReturn().response
        assertThat(response.status).isEqualTo(status)
        assertThat(response.getHeader("Content-Type")).startsWith("application/json")
        assertThat(mapper.readTree(response.contentAsString)["code"].asString()).isEqualTo(code)
    }

    @Test fun `재연결 GET 수신 상한 초과는 SERVICE_UNAVAILABLE JSON 503이다`() {
        val story = stories.save(Story(title = "수신 상한", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id))
        val id = java.util.UUID.randomUUID().toString()
        Mockito.doReturn(true).`when`(generationEvents).exists(chat.publicId.toString(), id)
        Mockito.doThrow(org.springframework.core.task.TaskRejectedException("receiver limit"))
            .`when`(receivers).open(chat.publicId.toString(), id)
        assertResumeResponse(chat.publicId.toString(), id, 503, "SERVICE_UNAVAILABLE")
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).isEmpty()
        Mockito.verifyNoInteractions(executor)
    }

    @Test fun `재연결 GET 스트림 존재 확인 Redis 오류는 SERVICE_UNAVAILABLE JSON 503이다`() {
        val story = stories.save(Story(title = "스트림 장애", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id))
        val id = java.util.UUID.randomUUID().toString()
        Mockito.doThrow(org.springframework.data.redis.RedisConnectionFailureException("Redis down"))
            .`when`(generationEvents).exists(chat.publicId.toString(), id)
        assertResumeResponse(chat.publicId.toString(), id, 503, "SERVICE_UNAVAILABLE")
        Mockito.verifyNoInteractions(receivers, executor)
    }

    @Test fun `재연결 GET 생성 중 키 조회 Redis 오류는 SERVICE_UNAVAILABLE JSON 503이다`() {
        val story = stories.save(Story(title = "생성 키 장애", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id))
        val id = java.util.UUID.randomUUID().toString()
        Mockito.doReturn(false).`when`(generationEvents).exists(chat.publicId.toString(), id)
        Mockito.doThrow(org.springframework.data.redis.RedisConnectionFailureException("Redis down"))
            .`when`(store).get(chat.publicId.toString())
        assertResumeResponse(chat.publicId.toString(), id, 503, "SERVICE_UNAVAILABLE")
        Mockito.verifyNoInteractions(receivers, executor)
    }

    @Test fun `재연결 GET의 채팅 없음 404와 소유권 403은 수신 및 Redis 장애보다 먼저다`() {
        val (_, chat, _) = paidChat()
        val id = java.util.UUID.randomUUID().toString()
        Mockito.doThrow(org.springframework.data.redis.RedisConnectionFailureException("Redis down"))
            .`when`(generationEvents).exists(Mockito.anyString(), Mockito.anyString())
        Mockito.doThrow(org.springframework.data.redis.RedisConnectionFailureException("Redis down"))
            .`when`(store).get(Mockito.anyString())
        Mockito.doThrow(org.springframework.core.task.TaskRejectedException("receiver limit"))
            .`when`(receivers).open(Mockito.anyString(), Mockito.anyString())
        Mockito.clearInvocations(generationEvents, store, receivers)
        assertResumeResponse(java.util.UUID.randomUUID().toString(), id, 404, "NOT_FOUND")
        assertResumeResponse(chat.publicId.toString(), id, 403, "FORBIDDEN")
        Mockito.verifyNoInteractions(generationEvents, store, receivers, executor)
    }

    @Test fun `재연결 GET은 채팅 접근을 먼저 판정하고 만료 또는 잘못된 생성에는 NOT_FOUND를 반환한다`() {
        val (_, chat, _) = paidChat()
        val id = java.util.UUID.randomUUID().toString()
        org.assertj.core.api.Assertions.assertThatThrownBy { service.resumeGeneration(chat.publicId.toString(), id, null) }
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException::class.java) { assertThat(it.statusCode.value()).isEqualTo(403) }
        org.assertj.core.api.Assertions.assertThatThrownBy { service.resumeGeneration(chat.publicId.toString(), id, chat.userId) }
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException::class.java) { assertThat(it.statusCode.value()).isEqualTo(404) }
        org.assertj.core.api.Assertions.assertThatThrownBy { service.resumeGeneration(chat.publicId.toString(), id, requireNotNull(chat.userId) + 100) }
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException::class.java) { assertThat(it.statusCode.value()).isEqualTo(403) }
        val forbidden = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/chats/${chat.publicId}/generations/$id/stream")).andReturn().response
        assertThat(forbidden.status).isEqualTo(403)
        val guest = chats.save(StoryChat(storyId = chat.storyId))
        org.assertj.core.api.Assertions.assertThatThrownBy { service.resumeGeneration(guest.publicId.toString(), id, chat.userId) }
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException::class.java) { assertThat(it.statusCode.value()).isEqualTo(403) }
        val missing = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/chats/${guest.publicId}/generations/$id/stream")).andReturn()
        assertThat(missing.response.status).isEqualTo(404)
        assertThat(mapper.readTree(missing.response.contentAsString)["code"].asString()).isEqualTo("NOT_FOUND")
    }

    @Test fun `큐 대기 첫 POST와 상세 직후 GET은 같은 생성의 처음부터 읽고 재연결은 추가 차감하지 않는다`() {
        val story = stories.save(Story(title = "재연결", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id))
        val post = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/chats/${chat.publicId.toString().uppercase()}/turns/stream")
            .header("X-Manyak-Device-Id", "resume-device").contentType("application/json").content("""{"userInput":"진행","realtimeImage":false}""")).andReturn()
        assertThat(post.request.isAsyncStarted).isTrue()
        val generation = service.getChatDetail(chat.publicId.toString(), null).generatingTurn!!
        assertThat(redis.hasKey("chat:${chat.publicId}:gen:${generation.generationId}")).isFalse()
        val get = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/chats/${chat.publicId}/generations/${generation.generationId}/stream")).andReturn()
        assertThat(get.request.isAsyncStarted).isTrue()
        val before = transactions.count()
        worker.task.run()
        post.getAsyncResult(8000)
        get.getAsyncResult(8000)
        val first = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(post)).andReturn().response.contentAsString
        val replay = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(get)).andReturn().response.contentAsString
        assertThat(first).contains("event:started", generation.generationId, "event:completed")
        assertThat(replay).isEqualTo(first)
        val done = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/chats/${chat.publicId}/generations/${generation.generationId}/stream")).andReturn()
        done.getAsyncResult(8000)
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(done)).andReturn().response.contentAsString).isEqualTo(first)
        assertThat(transactions.count()).isEqualTo(before)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)
    }

    @Test fun `이벤트 기록 오류가 계속되어도 유료 턴 저장과 과금을 유지하고 생성당 한번만 로그를 남긴다`() {
        val (member, chat, _) = paidChat()
        val emitter = start(member, chat)
        val scriptError = org.springframework.data.redis.RedisSystemException("event write failed", IllegalStateException())
        Mockito.doThrow(scriptError).`when`(redis).execute(Mockito.any(org.springframework.data.redis.core.script.RedisScript::class.java), Mockito.anyList<String>(), *arrayOf(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
        ai.tokenAfterClosure = true
        worker.task.run()
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)
        assertThat(wallet.balanceOf(member.id)).isZero()
        assertThat(refunds()).isEmpty()
        Mockito.verify(logger).event("chat_generation_event_write_failed", mapOf("chat_id" to chat.publicId.toString(), "error" to "RedisSystemException"))
        terminate(emitter, "completionCallback")
    }

    @Test fun `재생성 첫 연결도 큐에서 기다리며 완료와 실패는 보관된 스트림으로 구분하고 재수신은 무료다`() {
        for (fail in listOf(false, true)) {
            cleaner.cleanAll()
            val (member, chat, _) = paidChat()
            val turn = messages.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "기존 응답", messageOrder = 2))
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "기존 입력", messageOrder = 1))
            chat.currentTurn = 1
            chats.save(chat)
            val first = service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(turn.id, realtimeImage = false), member.id)
            val id = state(chat, member)!!.generationId
            assertThat(redis.hasKey("chat:${chat.publicId}:gen:$id")).isFalse()
            val reading = service.resumeGeneration(chat.publicId.toString(), id, member.id)
            if (fail) ai.beforeCompleted = { throw ChatTurnAiException("AI_TEST", "실패") }
            worker.task.run()
            val before = transactions.count()
            val balance = wallet.balanceOf(member.id)
            val replay = mutableListOf<String>()
            generationEvents.receive(chat.publicId.toString(), id, { true }) { name, _ -> replay.add(name) }
            assertThat(replay).containsExactly("started", if (fail) "error" else "completed")
            service.resumeGeneration(chat.publicId.toString(), id, member.id)
            assertThat(transactions.count()).isEqualTo(before)
            assertThat(wallet.balanceOf(member.id)).isEqualTo(balance)
            assertThat(state(chat, member)).isNull()
            assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(1)
            assertThat(messages.findById(turn.id).orElseThrow().content).isEqualTo(if (fail) "기존 응답" else "늦게 도착한 완료 결과")
            terminate(first, "completionCallback")
            terminate(reading, "completionCallback")
            ai.beforeCompleted = {}
        }
    }

    @Test fun `완료 스트림 TTL 만료와 잘못된 UUID 및 삭제된 채팅의 재연결은 HTTP 404다`() {
        val story = stories.save(Story(title = "만료 재연결", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id))
        service.streamChatTurn(chat.publicId.toString(), input(), deviceId = "expiry-device")
        val id = service.getChatDetail(chat.publicId.toString(), null).generatingTurn!!.generationId
        worker.task.run()
        val key = "chat:${chat.publicId}:gen:$id"
        assertThat(redis.getExpire(key)).isBetween(298, 300)
        redis.expire(key, java.time.Duration.ZERO)
        for (path in listOf(
            "/api/v1/chats/${chat.publicId}/generations/$id/stream",
            "/api/v1/chats/${chat.publicId}/generations/invalid/stream",
            "/api/v1/chats/invalid/generations/$id/stream",
            "/api/v1/chats/${java.util.UUID.randomUUID()}/generations/$id/stream",
        )) {
            val response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn().response
            assertThat(response.status).isEqualTo(404)
            assertThat(mapper.readTree(response.contentAsString)["code"].asString()).isEqualTo("NOT_FOUND")
        }
        service.deleteChat(chat.publicId.toString(), null)
        val response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/chats/${chat.publicId}/generations/$id/stream")).andReturn().response
        assertThat(response.status).isEqualTo(404)
    }

    @Test fun `SAVING 중 스트림과 생성 키가 사라져도 턴 저장과 과금은 유지하고 재연결은 404다`() {
        val (member, chat, _) = paidChat()
        val first = start(member, chat)
        val id = state(chat, member)!!.generationId
        val key = "chat:${chat.publicId}:gen:$id"
        Mockito.doAnswer { call ->
            deadline()
            redis.expire(key, java.time.Duration.ZERO)
            redis.expire("chat:${chat.publicId}:generating", java.time.Duration.ZERO)
            assertThat(redis.hasKey(key)).isFalse()
            assertThat(state(chat, member)).isNull()
            // 기한 뒤 없는 스트림에 APPEND가 0을 반환하는 경계만 대체한다.
            // 실제 Redis TIME/Lua의 재생성 방지는 ChatGenerationEventsTests에서 검증한다.
            Mockito.doReturn(0L).`when`(redis).execute(
                Mockito.any(org.springframework.data.redis.core.script.RedisScript::class.java),
                Mockito.eq(listOf(key)), *arrayOf(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()),
            )
            call.callRealMethod()
        }.`when`(images).validate(
            Mockito.any(ChatRealtimeImageService.Reservation::class.java) ?: ChatRealtimeImageService.Reservation(null),
            Mockito.any(ChatTurnAiResult::class.java) ?: ChatTurnAiResult("", emptyList()),
        )
        worker.task.run()
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)
        assertThat(wallet.balanceOf(member.id)).isZero()
        assertThat(refunds()).isEmpty()
        assertThat(redis.hasKey(key)).isFalse()
        org.assertj.core.api.Assertions.assertThatThrownBy { service.resumeGeneration(chat.publicId.toString(), id, member.id) }
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException::class.java) { assertThat(it.statusCode.value()).isEqualTo(404) }
        terminate(first, "completionCallback")
    }

    private fun streamRecords(chat: StoryChat, generationId: String) =
        redis.opsForStream<String, String>().range("chat:${chat.publicId}:gen:$generationId", org.springframework.data.domain.Range.unbounded()).orEmpty().joinToString { it.value.toString() }

    private fun failureCount() = meters.find("manyak.chat.turn.result").tag("outcome", ChatService.OUTCOME_FAILURE).counter()?.count() ?: 0.0

    private fun cancelledCount() = meters.find("manyak.chat.turn.result").tag("outcome", ChatService.OUTCOME_CANCELLED).counter()?.count() ?: 0.0

    private fun paidChat(): Triple<User, StoryChat, Long> {
        val member = users.save(User(nickname = "취소회원", status = UserStatus.ACTIVE))
        while (trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        val cost = chatModelService.litePrice()
        wallet.reward(member.id, cost, CreditReason.SIGNUP_REWARD, "seed:${member.id}")
        return Triple(member, newChat(member.id), cost)
    }

    private fun newChat(userId: Long): StoryChat {
        val story = stories.save(Story(title = "취소 테스트", genre = "판타지"))
        return chats.save(StoryChat(storyId = story.id, userId = userId))
    }

    private fun terminate(emitter: SseEmitter, callback: String) {
        // Servlet 컨테이너가 호출하는 실제 콜백을 결정적으로 실행해 120초 대기를 피한다.
        val handler = ReflectionTestUtils.getField(emitter, callback)!!
        if (handler is Runnable) handler.run()
        else {
            @Suppress("UNCHECKED_CAST")
            (handler as Consumer<Throwable>).accept(IllegalStateException("connection closed (test)"))
        }
        (ReflectionTestUtils.getField(emitter, "completionCallback") as Runnable).run()
    }
}

@TestConfiguration
class ChatTurnStreamCancellationTestConfig {
    @Bean @Primary
    fun ai() = ChatTurnStreamCancellationIntegrationTests.ControlledAi()
}
