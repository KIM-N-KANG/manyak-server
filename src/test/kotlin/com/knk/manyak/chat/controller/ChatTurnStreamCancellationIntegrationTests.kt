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

@Import(ChatTurnStreamCancellationTestConfig::class)
@ActiveProfiles("test")
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:manyak-stream-cancel;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
])
class ChatTurnStreamCancellationIntegrationTests {
    class ControlledWorker : Executor {
        lateinit var task: Runnable
        override fun execute(command: Runnable) { task = command }
    }

    class ControlledAi : ChatTurnAiClient {
        var beforeCompleted: () -> Unit = {}
        var characterImageAfterCancellation = false
        var returnedCompleted = false
        override fun streamTurn(
            request: ChatTurnAiRequest, traceLink: AiTraceLink,
            onCharacterImage: (ChatCharacterImageEvent) -> Unit, onToken: (String) -> Unit,
        ): ChatTurnAiResult {
            beforeCompleted()
            if (characterImageAfterCancellation) onCharacterImage(ChatCharacterImageEvent("인물", "https://cdn.test/character.webp"))
            returnedCompleted = true
            // 종료 뒤 token 없이 ping·completed만 오는 상황: send 실패에 의존하면 저장을 막지 못한다.
            return ChatTurnAiResult("늦게 도착한 완료 결과", emptyList())
        }
        override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String, traceLink: AiTraceLink) =
            ChatChoicesResult(emptyList())
    }

    @Autowired lateinit var service: ChatService
    private val worker = ControlledWorker()
    @MockitoBean(name = "chatSseExecutor") lateinit var executor: Executor
    @Autowired lateinit var ai: ControlledAi
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var chats: StoryChatRepository
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
        cleaner.cleanAll()
        policy.refresh()
        ai.beforeCompleted = {}
        ai.characterImageAfterCancellation = false
        ai.returnedCompleted = false
    }

    @Test fun `RUNNING 타임아웃 뒤 completed가 도착해도 저장 없이 정확히 한 번 환불한다`() =
        cancelledBeforeSave("timeoutCallback")

    @Test fun `RUNNING 연결 종료 뒤 completed가 도착해도 저장 없이 정확히 한 번 환불한다`() =
        cancelledBeforeSave("completionCallback")

    @Test fun `RUNNING emitter 오류 뒤 completed가 도착해도 저장 없이 정확히 한 번 환불한다`() =
        cancelledBeforeSave("errorCallback")

    @Test fun `취소 뒤 인물 이미지 콜백은 AI 소비를 중단하고 저장 없이 환불한다`() {
        val (member, chat, cost) = paidChat()
        val emitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행", realtimeImage = false), member.id)
        ai.beforeCompleted = { terminate(emitter, "completionCallback") }
        ai.characterImageAfterCancellation = true
        worker.task.run()
        assertThat(ai.returnedCompleted).isFalse()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(transactions.findAll().filter { it.reason == CreditReason.REFUND }).hasSize(1)
    }

    private fun cancelledBeforeSave(callback: String) {
        val (member, chat, cost) = paidChat()
        val emitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행", realtimeImage = false), member.id)
        val before = cancelledCount()
        ai.beforeCompleted = { terminate(emitter, callback) }
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).isEmpty()
        assertThat(cancelledCount()).isEqualTo(before + 1)
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(transactions.findAll().filter { it.reason == CreditReason.REFUND }).hasSize(1)
    }

    @Test fun `저장 진입 후 취소는 저장을 완료하고 환불하지 않는다`() {
        val (member, chat, cost) = paidChat()
        val emitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행", realtimeImage = false), member.id)
        // 이미지 검증은 저장 트랜잭션의 첫 단계다. 저장 진입이 이긴 뒤 종료 콜백을 실행한다.
        Mockito.doAnswer { call ->
            terminate(emitter, "completionCallback")
            call.callRealMethod()
        }.`when`(images).validate(
            Mockito.any(ChatRealtimeImageService.Reservation::class.java) ?: ChatRealtimeImageService.Reservation(null),
            Mockito.any(ChatTurnAiResult::class.java) ?: ChatTurnAiResult("", emptyList()),
        )
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(1)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)
        assertThat(wallet.balanceOf(member.id)).isZero()
        assertThat(transactions.findAll().filter { it.reason == CreditReason.CHAT_TURN }.single().amount).isEqualTo(-cost)
        assertThat(transactions.findAll().filter { it.reason == CreditReason.REFUND }).isEmpty()
    }

    @Test fun `취소된 회원 턴 체험은 중복 종료에도 한 번만 복원한다`() {
        val member = users.save(User(nickname = "체험회원", status = UserStatus.ACTIVE))
        while (trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        trials.restoreMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)
        val chat = newChat(member.id)
        val emitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행", realtimeImage = false), member.id)
        ai.beforeCompleted = { terminate(emitter, "completionCallback") }
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()
        assertThat(transactions.findAll()).isEmpty()
    }

    @Test fun `재생성 취소 뒤 completed가 도착해도 활성본을 유지하고 환불한다`() {
        val (member, chat, cost) = paidChat()
        chat.currentTurn = 1
        chats.save(chat)
        messages.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "기존 입력", messageOrder = 1))
        val assistant = messages.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "기존 본문", messageOrder = 2))
        val emitter = service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(assistant.id, realtimeImage = false), member.id)
        ai.beforeCompleted = { terminate(emitter, "completionCallback") }
        worker.task.run()
        assertThat(messages.findById(assistant.id).orElseThrow().content).isEqualTo("기존 본문")
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(transactions.findAll().filter { it.reason == CreditReason.REFUND }).hasSize(1)
    }

    @Test fun `PENDING 취소는 AI를 호출하지 않고 한 번 환불한다`() {
        val (member, chat, cost) = paidChat()
        val emitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행", realtimeImage = false), member.id)
        ai.beforeCompleted = { error("취소된 작업이 AI를 호출함") }
        terminate(emitter, "completionCallback")
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(transactions.findAll().filter { it.reason == CreditReason.REFUND }).hasSize(1)
    }

    @Test fun `게스트 취소는 마지막 턴 체험을 정확히 한 번 복원한다`() {
        val device = "cancel-guest-${java.util.UUID.randomUUID()}"
        while (trials.reserve(device, GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        trials.restore(device, GuestTrialLimitService.Counter.CHAT_TURN)
        val story = stories.save(Story(title = "게스트 취소", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id))
        val emitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행", realtimeImage = false), deviceId = device)
        ai.beforeCompleted = { terminate(emitter, "completionCallback") }
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(trials.reserve(device, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        assertThat(trials.reserve(device, GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()
    }

    @Test fun `취소는 유료 턴과 이미지 차감을 각각 한 번 환불한다`() {
        val (member, chat, cost) = paidChat()
        // 기본 이미지 비용은 0이므로 유료 정책을 명시해 환불 경로를 검증한다.
        policies.save(com.knk.manyak.credit.entity.CreditPolicy(policyKey = CreditPolicyKey.CHAT_IMAGE_COST.storageKey, amount = 7))
        policy.refresh()
        val imageCost = policy.amountOf(CreditPolicyKey.CHAT_IMAGE_COST)
        wallet.reward(member.id, imageCost, CreditReason.SIGNUP_REWARD, "image-seed:${member.id}")
        Mockito.doReturn(ChatRealtimeImageService.Reservation(null, userId = member.id, cost = imageCost))
            .`when`(images).prepare(true, member.id, null)
        val emitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행"), member.id)
        ai.beforeCompleted = { terminate(emitter, "timeoutCallback") }
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost + imageCost)
        val refunds = transactions.findAll().filter { it.reason == CreditReason.REFUND }
        assertThat(refunds).hasSize(2)
        assertThat(refunds.map { it.refType }).containsExactlyInAnyOrder("CHAT", "CHAT_IMAGE")
        assertThat(refunds.sumOf { it.amount }).isEqualTo(cost + imageCost)
    }

    @Test fun `취소된 이미지 체험은 중복 종료에도 한 번만 복원한다`() {
        val (member, chat, cost) = paidChat()
        while (trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)) { /* drain */ }
        trials.restoreMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)
        Mockito.doAnswer {
            assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)).isTrue()
            ChatRealtimeImageService.Reservation(null, userId = member.id, trialReserved = true)
        }.`when`(images).prepare(true, member.id, null)
        val emitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("진행"), member.id)
        ai.beforeCompleted = { terminate(emitter, "completionCallback") }
        worker.task.run()
        assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isZero()
        assertThat(wallet.balanceOf(member.id)).isEqualTo(cost)
        assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)).isTrue()
        assertThat(trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_IMAGE)).isFalse()
    }

    private fun cancelledCount() = meters.find("manyak.chat.turn.result").tag("outcome", ChatService.OUTCOME_CANCELLED).counter()?.count() ?: 0.0

    private fun paidChat(): Triple<User, StoryChat, Long> {
        val member = users.save(User(nickname = "취소회원", status = UserStatus.ACTIVE))
        while (trials.reserveMember(member.id, GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        val cost = policy.amountOf(CreditPolicyKey.CHAT_TURN_COST)
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
