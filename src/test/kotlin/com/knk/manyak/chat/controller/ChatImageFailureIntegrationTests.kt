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

    @BeforeEach fun setup() {
        cleaner.cleanAll()
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
