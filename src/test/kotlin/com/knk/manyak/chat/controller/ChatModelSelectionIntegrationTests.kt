package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.dto.RegenerateChatRequest
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.*
import com.knk.manyak.chat.service.ChatService
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.*
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatedChatTurnAiClientConfig::class)
class ChatModelSelectionIntegrationTests {
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    @Autowired private lateinit var versions: StoryMessageVersionRepository
    @Autowired private lateinit var models: ChatModelRepository
    @Autowired private lateinit var transactions: CreditTransactionRepository
    @Autowired private lateinit var wallet: CreditWalletService
    @Autowired private lateinit var trials: GuestTrialLimitService
    @Autowired private lateinit var service: ChatService

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var publisher: com.knk.manyak.global.observability.analytics.AnalyticsEventPublisher
    @Autowired private lateinit var meters: io.micrometer.core.instrument.MeterRegistry
    private val logs = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
    private val log = org.slf4j.LoggerFactory.getLogger(com.knk.manyak.global.observability.StructuredLogger::class.java) as ch.qos.logback.classic.Logger

    @BeforeEach fun captureLogs() { logs.list = java.util.concurrent.CopyOnWriteArrayList(); logs.start(); log.addAppender(logs) }
    @AfterEach fun detachLogs() { log.detachAppender(logs); logs.stop() }

    private fun events(type: String) = org.mockito.Mockito.mockingDetails(publisher).invocations
        .filter { it.method.name == "publish" }
        .map { it.arguments[0] as com.knk.manyak.global.observability.analytics.AnalyticsEvent }
        .filter { it.eventType.endsWith(type) }

    @Test fun `AI 실패 이벤트는 설정 변경 전의 긴 출력 분량을 기록하고 성공 이벤트는 없다`() {
        val user = member()
        val chat = chat(user)
        service.changeSettings(chat.publicId.toString(), null, user.id, true)
        GatedChatTurnAiClientConfig.gatedInput = "실패 분량"
        GatedChatTurnAiClientConfig.turnFailure = IllegalStateException("test failure")
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("실패 분량", realtimeImage = false), user.id)
        assertThat(GatedChatTurnAiClientConfig.entered.await(10, TimeUnit.SECONDS)).isTrue()
        service.changeSettings(chat.publicId.toString(), null, user.id, false)
        GatedChatTurnAiClientConfig.gate.countDown()
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val properties = events("aiMessage_processed_failed").single().eventProperties
            assertThat(properties["long_response"]).isEqualTo(true)
            assertThat(properties["model_code"]).isEqualTo("AURORA")
            assertThat(properties).doesNotContainKey("turn_number")
            assertThat(wallet.balanceOf(user.id)).isEqualTo(1000)
        }
        assertThat(events("aiMessage_processed_succeeded")).isEmpty()
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).isEmpty()
    }

    @Test fun `턴 시작 분량은 설정 변경과 턴 저장 뒤에도 유지되고 다음 이어쓰기와 재생성에 새 값이 적용된다`() {
        val user = member()
        val chat = chat(user)
        GatedChatTurnAiClientConfig.gatedInput = "분량 변경 중"
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("분량 변경 중", realtimeImage = false), user.id)
        assertThat(GatedChatTurnAiClientConfig.entered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.longResponse).isFalse()
        service.changeSettings(chat.publicId.toString(), null, user.id, true)
        GatedChatTurnAiClientConfig.gate.countDown()
        waitForTurn(chat)
        await().atMost(Duration.ofSeconds(10)).until { service.getChatDetail(chat.publicId.toString(), user.id).generatingTurn == null }
        assertThat(chats.findById(chat.id).orElseThrow().longResponse).isTrue()
        assertThat(logs.list.any { it.formattedMessage.contains("event_name=ai_response_saved") && it.formattedMessage.contains("long_response=false") }).isTrue()
        val first = messages.findByChatIdOrderByMessageOrderAsc(chat.id).last()
        service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(first.id, false), user.id)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(chats.findById(chat.id).orElseThrow().regeneratedCount).isEqualTo(1)
            assertThat(logs.list.any { it.formattedMessage.contains("event_name=ai_response_regenerated") && it.formattedMessage.contains("long_response=true") }).isTrue()
        }
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.longResponse).isTrue()
        await().atMost(Duration.ofSeconds(10)).until { service.getChatDetail(chat.publicId.toString(), user.id).generatingTurn == null }
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("다음 긴 응답", realtimeImage = false), user.id)
        waitForTurn(chat, 2)
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.longResponse).isTrue()
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val succeeded = events("aiMessage_processed_succeeded")
            assertThat(succeeded).hasSize(3)
            assertThat(succeeded.map { it.eventProperties["long_response"] }).containsExactly(false, true, true)
            assertThat(succeeded.map { it.eventProperties["is_regenerated"] }).containsExactly(false, true, false)
        }
        assertThat(meters.find("manyak.chat.turn.result").meters().flatMap { it.id.tags }.none { it.key == "long_response" }).isTrue()
    }

    @Test fun `회원 체험과 게스트도 저장한 긴 출력 분량을 AI에 보낸다`() {
        val user = member(exhaust = false)
        for ((owner, device) in listOf(user to null, null to "long-response-guest")) {
            val chat = chat(owner)
            service.changeSettings(chat.publicId.toString(), null, owner?.id, true)
            service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("긴 체험", realtimeImage = false), owner?.id, device)
            waitForTurn(chat)
            assertThat(GatedChatTurnAiClientConfig.lastRequest!!.longResponse).isTrue()
            assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("LITE")
        }
    }

    @BeforeEach fun reset() { cleaner.cleanAll(); GatedChatTurnAiClientConfig.reset() }
    @AfterEach fun release() { GatedChatTurnAiClientConfig.gate.countDown(); GatedChatTurnAiClientConfig.reset() }

    private fun member(exhaust: Boolean = true): User {
        val user = users.save(User(nickname = "모델회원", status = UserStatus.ACTIVE))
        wallet.reward(user.id, 1000, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        if (exhaust) repeat(5) { trials.reserveMember(user.id, GuestTrialLimitService.Counter.CHAT_TURN) }
        return user
    }
    private fun chat(user: User?) = chats.save(StoryChat(
        storyId = stories.save(Story(title = "모델 스토리")).id, userId = user?.id, modelCode = "AURORA",
    ))
    private fun waitForTurn(chat: StoryChat, count: Int = 1) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(chats.findById(chat.id).orElseThrow().currentTurn).isEqualTo(count)
        }
    }

    @Test fun `기본 모델이 없으면 활성 모델 채팅도 과금 전에 실패한다`() {
        val user = member()
        val chat = chat(user)
        models.save(models.findById("AURORA").orElseThrow().apply { isDefault = false })
        org.assertj.core.api.Assertions.assertThatThrownBy {
            service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("모델 오류", realtimeImage = false), user.id)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(transactions.findAll().none { it.reason == CreditReason.CHAT_TURN }).isTrue()
    }

    @Test fun `비활성 라이트 채팅의 유료 턴은 기본 오로라로 처리하고 저장한 모델은 유지한다`() {
        val user = member()
        val story = stories.save(Story(title = "비활성 모델 턴"))
        val chat = chats.save(StoryChat(storyId = story.id, userId = user.id, modelCode = "LITE"))
        models.save(models.findById("LITE").orElseThrow().apply { isActive = false })
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("기본 모델 턴", realtimeImage = false), user.id)
        waitForTurn(chat)
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("AURORA")
        assertThat(wallet.balanceOf(user.id)).isEqualTo(950)
        assertThat(chats.findById(chat.id).orElseThrow().modelCode).isEqualTo("LITE")
    }

    @Test fun `오로라 유료 턴 중 모델과 가격을 변경해도 원래 가격으로 저장하고 다음 재생성부터 라이트를 쓴다`() {
        val user = member()
        val chat = chat(user)
        GatedChatTurnAiClientConfig.gatedInput = "이어쓰기"
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("이어쓰기", realtimeImage = false), user.id)
        assertThat(GatedChatTurnAiClientConfig.entered.await(10, TimeUnit.SECONDS)).isTrue()
        val started = chats.findById(chat.id).orElseThrow().updatedAt
        service.changeSettings(chat.publicId.toString(), "LITE", user.id)
        assertThat(chats.findById(chat.id).orElseThrow().updatedAt).isEqualTo(started)
        models.save(models.findById("AURORA").orElseThrow().apply { turnCost = 90 })
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("AURORA")
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.userId).isEqualTo(user.publicId.toString())
        GatedChatTurnAiClientConfig.gate.countDown()
        waitForTurn(chat)
        val first = messages.findByChatIdOrderByMessageOrderAsc(chat.id).last()
        val chargeA = transactions.findById(first.creditTransactionId!!).orElseThrow()
        assertThat(chargeA.amount).isEqualTo(-50)
        assertThat(chargeA.idempotencyKey).isNull()
        assertThat(chargeA.refType).isEqualTo("CHAT_TURN")
        assertThat(chats.findById(chat.id).orElseThrow().modelCode).isEqualTo("LITE")
        service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(first.id, false), user.id)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(chats.findById(chat.id).orElseThrow().regeneratedCount).isEqualTo(1)
        }
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("LITE")
        assertThat(versions.findAll().single().creditTransactionId).isEqualTo(chargeA.id)
        val chargeB = messages.findById(first.id).orElseThrow().creditTransactionId!!
        assertThat(transactions.findById(chargeB).orElseThrow().amount).isEqualTo(-20)
        assertThat(transactions.findById(chargeB).orElseThrow().idempotencyKey).isNull()
        assertThat(transactions.findById(chargeB).orElseThrow().refType).isEqualTo("CHAT_TURN")
        assertThat(wallet.balanceOf(user.id)).isEqualTo(930)
    }

    @Test fun `회원 체험은 선택 모델에 관계없이 라이트와 회원 공개 식별자를 보내고 차감 연결이 없다`() {
        val user = member(exhaust = false)
        val chat = chat(user)
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("회원 체험", realtimeImage = false), user.id)
        waitForTurn(chat)
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("LITE")
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.userId).isEqualTo(user.publicId.toString())
        assertThat(wallet.balanceOf(user.id)).isEqualTo(1000)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id).last().creditTransactionId).isNull()
    }

    @Test fun `게스트는 선택 모델을 보관하면서 라이트로 호출하고 user_id는 null이다`() {
        val chat = chat(null)
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("게스트 체험", realtimeImage = false), deviceId = "model-guest")
        waitForTurn(chat)
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("LITE")
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.userId).isNull()
        assertThat(chats.findById(chat.id).orElseThrow().modelCode).isEqualTo("AURORA")
        assertThat(transactions.findAll()).isEmpty()
    }
}
