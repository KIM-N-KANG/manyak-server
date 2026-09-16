package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatTurnAiException
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.service.ChatRealtimeImageService
import com.knk.manyak.chat.service.ChatService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.credit.service.GuestTrialLimitService.Counter
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 실제 서비스·DB·Redis 테스트 구현을 사용하고 executor와 서블릿 종료 통지만 결정적으로 제어한다. */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@Import(GatedChatTurnAiClientConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatImageFailureIntegrationTests {
    @Autowired private lateinit var service: ChatService
    @Autowired private lateinit var images: ChatRealtimeImageService
    @Autowired private lateinit var trials: GuestTrialLimitService
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private lateinit var originalExecutor: Executor
    private lateinit var observedTrials: GuestTrialLimitService
    private val queued = AtomicReference<Runnable>()
    private lateinit var chat: StoryChat
    private val device = "image-failure-device"

    @Autowired private lateinit var users: com.knk.manyak.auth.repository.UserRepository
    @Autowired private lateinit var policies: com.knk.manyak.credit.repository.CreditPolicyRepository
    @Autowired private lateinit var policy: com.knk.manyak.credit.service.CreditPolicyService
    @Autowired private lateinit var wallet: com.knk.manyak.credit.service.CreditWalletService
    @Autowired private lateinit var ledger: com.knk.manyak.credit.repository.CreditTransactionRepository

    private fun paidMember(cost: Long = 30, balance: Long = 100, drainTurn: Boolean = true, drainImage: Boolean = true): Long {
        policies.save(com.knk.manyak.credit.entity.CreditPolicy(policyKey = "chat_image_cost", amount = cost))
        policies.save(com.knk.manyak.credit.entity.CreditPolicy(policyKey = "chat_turn_cost", amount = 20))
        policy.refresh()
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "유료이미지회원"))
        if (drainTurn) while (trials.reserveMember(user.id, Counter.CHAT_TURN)) { }
        if (drainImage) while (trials.reserveMember(user.id, Counter.CHAT_IMAGE)) { }
        if (balance > 0) wallet.reward(user.id, balance, com.knk.manyak.credit.entity.CreditReason.SIGNUP_REWARD, "seed")
        chat = chats.save(StoryChat(storyId = chat.storyId, userId = user.id))
        return user.id
    }
    private fun paidStart(userId: Long) = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest(userInput = "유료"), userId = userId)
    private fun spends() = ledger.findAll().filter { it.amount < 0 }
    private fun refunds() = ledger.findAll().filter { it.reason == com.knk.manyak.credit.entity.CreditReason.REFUND }

    @Test fun `유료 이미지와 턴을 한꺼번에 차감한 뒤 슬롯을 전달한다`() {
        val user = paidMember()
        paidStart(user)
        assertThat(wallet.balanceOf(user)).isEqualTo(50)
        assertThat(spends().map { it.refType }).containsExactlyInAnyOrder("CHAT", "CHAT_IMAGE")
        queued.get().run()
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.imageSlots).hasSize(1)
        assertThat(refunds()).isEmpty()
    }
    @Test fun `턴 체험이 남아도 유료 이미지는 독립적으로 차감한다`() {
        val user = paidMember(drainTurn = false)
        paidStart(user)
        queued.get().run()
        assertThat(wallet.balanceOf(user)).isEqualTo(70)
        assertThat(spends().map { it.refType }).containsExactly("CHAT_IMAGE")
        assertThat(trials.usage(user, null, Counter.CHAT_TURN).used).isEqualTo(1)
        assertThat(refunds()).isEmpty()
    }

    @Test fun `0원 이미지에는 차감과 환불 원장이 없다`() {
        val user = paidMember(cost = 0)
        GatedChatTurnAiClientConfig.realtimeExists = false
        paidStart(user)
        queued.get().run()
        assertThat(wallet.balanceOf(user)).isEqualTo(80)
        assertThat(spends()).hasSize(1)
        assertThat(refunds()).isEmpty()
    }
    @Test fun `이미지 실패는 이미지 비용만 환불하고 턴은 저장한다`() {
        val user = paidMember()
        GatedChatTurnAiClientConfig.realtimeExists = false
        val emitter = paidStart(user)
        queued.get().run()
        callback(emitter, "completionCallback")
        assertThat(wallet.balanceOf(user)).isEqualTo(80)
        assertThat(refunds().map { it.refType }).containsExactly("CHAT_IMAGE")
        assertThat(refunds().single().amount).isEqualTo(30)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)
    }
    @Test fun `본문 실패는 두 차감 행을 각각 한번 환불한다`() {
        val user = paidMember()
        GatedChatTurnAiClientConfig.turnFailure = ChatTurnAiException("AI_ERROR", "test")
        val emitter = paidStart(user)
        queued.get().run()
        callback(emitter, "completionCallback")
        assertThat(wallet.balanceOf(user)).isEqualTo(100)
        assertThat(refunds().map { it.refType }).containsExactlyInAnyOrder("CHAT", "CHAT_IMAGE")
        assertThat(refunds().map { it.idempotencyKey }.distinct()).hasSize(2)
    }
    @Test fun `유료 큐 취소는 두 차감 행을 각각 한번 환불한다`() {
        val user = paidMember()
        val emitter = paidStart(user)
        callback(emitter, "timeoutCallback")
        callback(emitter, "completionCallback")
        queued.get().run()
        assertThat(wallet.balanceOf(user)).isEqualTo(100)
        assertThat(refunds()).hasSize(2)
        assertThat(GatedChatTurnAiClientConfig.lastRequest).isNull()
    }
    @Test fun `이미지 환불 뒤 턴 롤백이면 이미지 환불을 다시 커밋한다`() {
        val user = paidMember()
        GatedChatTurnAiClientConfig.realtimeExists = false
        GatedChatTurnAiClientConfig.onRealtimeHead = {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) { error("rollback image refund too") }
            })
        }
        paidStart(user)
        queued.get().run()
        assertThat(wallet.balanceOf(user)).isEqualTo(100)
        assertThat(refunds().map { it.refType }).containsExactlyInAnyOrder("CHAT", "CHAT_IMAGE")
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).isEmpty()
    }
    @Test fun `이미지 비용 부족이면 예약한 턴 체험을 복원한다`() {
        val user = paidMember(balance = 10, drainTurn = false)
        org.assertj.core.api.Assertions.assertThatThrownBy { paidStart(user) }
            .isInstanceOf(com.knk.manyak.credit.InsufficientCreditException::class.java)
        assertThat(trials.usage(user, null, Counter.CHAT_TURN).used).isZero()
        assertThat(spends()).isEmpty()
        assertThat(refunds()).isEmpty()
    }
    @Test fun `턴 비용 부족이면 예약한 이미지 체험을 복원한다`() {
        val user = paidMember(balance = 10, drainImage = false)
        org.assertj.core.api.Assertions.assertThatThrownBy { paidStart(user) }
            .isInstanceOf(com.knk.manyak.credit.InsufficientCreditException::class.java)
        assertThat(trials.usage(user, null, Counter.CHAT_IMAGE).used).isZero()
        assertThat(spends()).isEmpty()
    }

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        policy.refresh()
        GatedChatTurnAiClientConfig.reset()
        GatedChatTurnAiClientConfig.realtimeEnabled = true
        GatedChatTurnAiClientConfig.realtimeExists = true
        originalExecutor = ReflectionTestUtils.getField(service, "chatSseExecutor") as Executor
        ReflectionTestUtils.setField(service, "chatSseExecutor", Executor { queued.set(it) })
        observedTrials = spy(trials)
        ReflectionTestUtils.setField(images, "trials", observedTrials)
        val story = stories.save(Story(title = "이미지 실패 검증", genre = "판타지"))
        chat = chats.save(StoryChat(storyId = story.id))
    }

    @AfterEach fun cleanup() {
        ReflectionTestUtils.setField(service, "chatSseExecutor", originalExecutor)
        ReflectionTestUtils.setField(images, "trials", trials)
        GatedChatTurnAiClientConfig.reset()
    }

    private fun start(): SseEmitter = service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest(userInput = "살펴본다"), deviceId = device).also {
        assertThat(trials.usage(null, device, Counter.CHAT_IMAGE).used).isEqualTo(1)
        assertThat(trials.usage(null, device, Counter.CHAT_TURN).used).isEqualTo(1)
    }
    private fun callback(emitter: SseEmitter, field: String) {
        (ReflectionTestUtils.getField(emitter, field) as Runnable).run()
    }
    private fun assertRestored(times: Int, saved: Boolean) {
        verify(observedTrials, times(times)).restore(device, Counter.CHAT_IMAGE)
        assertThat(trials.usage(null, device, Counter.CHAT_IMAGE).used).isEqualTo(if (times == 0) 1 else 0)
        assertThat(trials.usage(null, device, Counter.CHAT_TURN).used).isEqualTo(if (saved) 1 else 0)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(if (saved) 2 else 0)
    }

    @Test fun `워커 시작 직전 취소가 선점하면 이미지와 턴을 복원하고 저장하지 않는다`() {
        val emitter = start()
        val beforeStart = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                beforeStart.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                queued.get().run()
            } catch (ex: Throwable) { failure.set(ex) }
        }
        worker.start()
        try {
            check(beforeStart.await(5, TimeUnit.SECONDS))
            callback(emitter, "timeoutCallback")
            callback(emitter, "completionCallback")
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertThat(worker.isAlive).isFalse()
        assertThat(failure.get()).isNull()
        assertRestored(1, false)
        assertThat(GatedChatTurnAiClientConfig.lastRequest).isNull()
    }

    @Test fun `실행 중 타임아웃은 워커 실패 종료에서 이미지 예약을 한번 복원한다`() {
        GatedChatTurnAiClientConfig.gatedInput = "살펴본다"
        val emitter = start()
        val worker = Thread { queued.get().run() }
        worker.start()
        try {
            check(GatedChatTurnAiClientConfig.entered.await(5, TimeUnit.SECONDS))
            callback(emitter, "timeoutCallback")
            callback(emitter, "completionCallback")
            verify(observedTrials, never()).restore(device, Counter.CHAT_IMAGE)
        } finally {
            GatedChatTurnAiClientConfig.gate.countDown()
            worker.join(5000)
        }
        assertThat(worker.isAlive).isFalse()
        assertRestored(1, false)
    }

    @Test fun `AI error는 이미지와 턴 예약을 한번 복원한다`() {
        GatedChatTurnAiClientConfig.turnFailure = ChatTurnAiException("AI_TIMEOUT", "test timeout")
        start()
        queued.get().run()
        assertRestored(1, false)
    }

    @Test fun `이미지 검증 성공 뒤 저장 롤백은 이미지 예약을 한번 복원한다`() {
        GatedChatTurnAiClientConfig.onRealtimeHead = {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) { error("test rollback") }
            })
        }
        start()
        queued.get().run()
        assertRestored(1, false)
    }

    @Test fun `이미지 검증 실패는 커밋 전에 복원하고 롤백 후에도 복원은 한번이다`() {
        GatedChatTurnAiClientConfig.realtimeExists = false
        var usedAtCommit: Long? = null
        GatedChatTurnAiClientConfig.onRealtimeHead = {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    usedAtCommit = trials.usage(null, device, Counter.CHAT_IMAGE).used
                    error("test rollback after invalid image")
                }
            })
        }
        start()
        queued.get().run()
        assertThat(usedAtCommit).isEqualTo(0)
        assertRestored(1, false)
    }

    @Test fun `저장 커밋 뒤 연결 종료는 이미지와 턴 체험을 복원하지 않는다`() {
        val emitter = start()
        GatedChatTurnAiClientConfig.onRealtimeHead = {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    emitter.complete()
                    callback(emitter, "completionCallback")
                }
            })
        }
        queued.get().run()
        assertRestored(0, true)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id).last().content).contains("[[https://cdn.test/chat-images/")
    }
}
