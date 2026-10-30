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

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatedChatTurnAiClientConfig::class)
class ChatModelSelectionIntegrationTests {
    @Autowired private lateinit var restTestClient: org.springframework.test.web.servlet.client.RestTestClient
    @Autowired private lateinit var jwtTokenProvider: com.knk.manyak.auth.jwt.JwtTokenProvider
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
    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var publisher: com.knk.manyak.global.observability.analytics.AnalyticsEventPublisher
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var generationEvents: com.knk.manyak.chat.service.ChatGenerationEvents
    @Autowired private lateinit var meters: io.micrometer.core.instrument.MeterRegistry
    private val logs = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
    private val log = org.slf4j.LoggerFactory.getLogger(com.knk.manyak.global.observability.StructuredLogger::class.java) as ch.qos.logback.classic.Logger

    @BeforeEach fun captureLogs() { logs.list = java.util.concurrent.CopyOnWriteArrayList(); logs.start(); log.addAppender(logs) }
    @AfterEach fun detachLogs() { log.detachAppender(logs); logs.stop() }

    private fun events(type: String) = org.mockito.Mockito.mockingDetails(publisher).invocations
        .filter { it.method.name == "publish" }
        .map { it.arguments[0] as com.knk.manyak.global.observability.analytics.AnalyticsEvent }
        .filter { it.eventType.endsWith(type) }

    private fun promotion(code: String = "LITE", cost: Long? = 0, start: java.time.Instant = java.time.Instant.now().minusSeconds(60), end: java.time.Instant = java.time.Instant.now().plusSeconds(3600)) {
        jdbc.update("UPDATE chat_models SET promo_turn_cost = ?, promo_starts_at = ?, promo_ends_at = ? WHERE code = ?",
            cost, if (cost == null) null else java.time.OffsetDateTime.ofInstant(start, java.time.ZoneOffset.UTC),
            if (cost == null) null else java.time.OffsetDateTime.ofInstant(end, java.time.ZoneOffset.UTC), code)
    }

    @Test fun `무료 프로모션은 체험 잔여와 소진 모두 원장 없이 이어쓰기와 재생성을 허용한다`() {
        promotion()
        for (exhaust in listOf(false, true)) {
            val user = member(exhaust = exhaust, balance = 1)
            wallet.deduct(user.id, 1, CreditReason.CHAT_TURN)
            val chat = chat(user)
            service.changeSettings(chat.publicId.toString(), "LITE", user.id)
            val used = trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used
            service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("무료", realtimeImage = false), user.id)
            waitForTurn(chat)
            await().atMost(Duration.ofSeconds(10)).until { service.getChatDetail(chat.publicId.toString(), user.id).generatingTurn == null }
            val first = messages.findByChatIdOrderByMessageOrderAsc(chat.id).last()
            assertThat(first.creditTransactionId).isNull()
            service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(first.id, false), user.id)
            await().atMost(Duration.ofSeconds(10)).until { chats.findById(chat.id).orElseThrow().regeneratedCount == 1 && service.getChatDetail(chat.publicId.toString(), user.id).generatingTurn == null }
            assertThat(messages.findById(first.id).orElseThrow().creditTransactionId).isNull()
            assertThat(trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isEqualTo(used)
            assertThat(wallet.balanceOf(user.id)).isZero()
            assertThat(transactions.findAll().count { it.userId == user.id && it.refType == "CHAT_TURN" }).isZero()
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(events("aiMessage_processed_succeeded").map { it.eventProperties["turn_cost"] }).hasSize(4).containsOnly(0L)
            assertThat(logs.list.any { it.formattedMessage.contains("event_name=ai_response_saved") && it.formattedMessage.contains("turn_cost=0") }).isTrue()
        }
        val guest = chat(null)
        service.streamChatTurn(guest.publicId.toString(), ContinueChatRequest("게스트", realtimeImage = false), deviceId = "promo-guest")
        waitForTurn(guest)
        assertThat(trials.usage(null, "promo-guest", GuestTrialLimitService.Counter.CHAT_TURN).used).isEqualTo(1)
    }

    @Test fun `양수 프로모션은 라이트 체험을 먼저 쓰고 소진 뒤 할인 가격과 오로라 할인 가격을 차감한다`() {
        promotion(cost = 10)
        promotion("AURORA", 30)
        for ((model, exhaust, expected) in listOf(Triple("LITE", false, 0L), Triple("LITE", true, 10L), Triple("AURORA", false, 30L))) {
            val user = member(exhaust = exhaust)
            val chat = chat(user)
            service.changeSettings(chat.publicId.toString(), model, user.id)
            service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("할인", realtimeImage = false), user.id)
            waitForTurn(chat)
            assertThat(wallet.balanceOf(user.id)).isEqualTo(1000 - expected)
            assertThat(trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isEqualTo(if (exhaust) 5 else if (model == "LITE") 1 else 0)
        }
    }

    @Test fun `턴 중 무료 프로모션 종료와 설정은 이미 확정한 가격을 바꾸지 않는다`() {
        for (freeAtStart in listOf(true, false)) {
            val user = member()
            val chat = chat(user)
            service.changeSettings(chat.publicId.toString(), "LITE", user.id)
            promotion(cost = if (freeAtStart) 0 else null)
            GatedChatTurnAiClientConfig.reset()
            GatedChatTurnAiClientConfig.gatedInput = "프로모션 경계"
            service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("프로모션 경계", realtimeImage = false), user.id)
            assertThat(GatedChatTurnAiClientConfig.entered.await(10, TimeUnit.SECONDS)).isTrue()
            if (freeAtStart) promotion(end = java.time.Instant.now().minusSeconds(1)) else promotion()
            GatedChatTurnAiClientConfig.gate.countDown()
            waitForTurn(chat)
            await().atMost(Duration.ofSeconds(10)).until { service.getChatDetail(chat.publicId.toString(), user.id).generatingTurn == null }
            assertThat(wallet.balanceOf(user.id)).isEqualTo(if (freeAtStart) 1000 else 980)
            val id = messages.findByChatIdOrderByMessageOrderAsc(chat.id).last().creditTransactionId
            if (freeAtStart) assertThat(id).isNull() else assertThat(transactions.findById(id!!).orElseThrow().amount).isEqualTo(-20)
        }
    }

    @Test fun `무료 프로모션 턴 실패는 체험 복원과 환불을 만들지 않는다`() {
        promotion()
        val user = member(exhaust = false)
        val chat = chat(user)
        service.changeSettings(chat.publicId.toString(), "LITE", user.id)
        GatedChatTurnAiClientConfig.turnFailure = IllegalStateException("promotion failure")
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("실패", realtimeImage = false), user.id)
        await().atMost(Duration.ofSeconds(10)).until { service.getChatDetail(chat.publicId.toString(), user.id).generatingTurn == null }
        assertThat(trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isZero()
        assertThat(transactions.findAll().map { it.reason }).containsExactly(CreditReason.SIGNUP_REWARD)
    }

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
            assertThat(logs.list.any {
                it.formattedMessage.contains("event_name=ai_response_saved") &&
                    it.formattedMessage.contains("turn_number=2") && it.formattedMessage.contains("long_response=true")
            }).isTrue()
            assertThat(succeeded).hasSize(3)
            assertThat(succeeded.map { it.eventProperties["long_response"] }).containsExactly(false, true, true)
            assertThat(succeeded.map { it.eventProperties["is_regenerated"] }).containsExactly(false, true, false)
        }
        assertThat(meters.find("manyak.chat.turn.result").meters().flatMap { it.id.tags }.none { it.key == "long_response" }).isTrue()
    }

    @Test fun `저장 뒤 완료 이벤트 전달이 실패해도 시작 분량의 성공 이벤트만 한번 발행한다`() {
        val user = member()
        val chat = chat(user)
        service.changeSettings(chat.publicId.toString(), null, user.id, true)
        GatedChatTurnAiClientConfig.gatedInput = "완료 전달 실패"
        val failedWrites = java.util.concurrent.atomic.AtomicInteger()
        val committedAtFailure = java.util.concurrent.atomic.AtomicBoolean()
        org.mockito.Mockito.doAnswer { factoryCall ->
            val writer = org.mockito.Mockito.spy(factoryCall.callRealMethod() as com.knk.manyak.chat.service.ChatGenerationEvents.Writer)
            org.mockito.Mockito.doAnswer { writeCall ->
                if (writeCall.getArgument<String>(0) == "completed") {
                    committedAtFailure.set(chats.findById(chat.id).orElseThrow().currentTurn == 1)
                    failedWrites.incrementAndGet()
                    throw IllegalStateException("completed delivery failed")
                }
                writeCall.callRealMethod()
            }.`when`(writer).write(org.mockito.Mockito.anyString(), org.mockito.Mockito.any<Any>() ?: Any())
            writer
        }.`when`(generationEvents).writer(
            org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(java.time.Instant::class.java) ?: java.time.Instant.EPOCH,
        )
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("완료 전달 실패", realtimeImage = false), user.id)
        assertThat(GatedChatTurnAiClientConfig.entered.await(10, TimeUnit.SECONDS)).isTrue()
        service.changeSettings(chat.publicId.toString(), null, user.id, false)
        GatedChatTurnAiClientConfig.gate.countDown()
        await().atMost(Duration.ofSeconds(10)).until {
            failedWrites.get() == 1 && service.getChatDetail(chat.publicId.toString(), user.id).generatingTurn == null
        }
        val succeeded = events("aiMessage_processed_succeeded")
        assertThat(succeeded).hasSize(1)
        assertThat(succeeded.single().eventProperties["long_response"]).isEqualTo(true)
        assertThat(events("aiMessage_processed_failed")).isEmpty()
        assertThat(committedAtFailure.get()).isTrue()
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)
        assertThat(wallet.balanceOf(user.id)).isEqualTo(950)
    }

    @Test fun `회원 체험과 게스트도 저장한 긴 출력 분량을 AI에 보낸다`() {
        val user = member(exhaust = false)
        for ((owner, device) in listOf(user to null, null to "long-response-guest")) {
            val chat = chat(owner)
            service.changeSettings(chat.publicId.toString(), "LITE", owner?.id, true)
            service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("긴 체험", realtimeImage = false), owner?.id, device)
            waitForTurn(chat)
            assertThat(GatedChatTurnAiClientConfig.lastRequest!!.longResponse).isTrue()
            assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("LITE")
        }
    }

    @BeforeEach fun reset() { cleaner.cleanAll(); GatedChatTurnAiClientConfig.reset() }
    @AfterEach fun release() { GatedChatTurnAiClientConfig.gate.countDown(); GatedChatTurnAiClientConfig.reset() }

    private fun member(exhaust: Boolean = true, balance: Long = 1000): User {
        val user = users.save(User(nickname = "모델회원", status = UserStatus.ACTIVE))
        wallet.reward(user.id, balance, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
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

    private fun assertResponseModel(chat: StoryChat, model: String) {
        assertThat(jdbc.queryForList("SELECT model_code FROM story_messages WHERE chat_id = ? AND role = 'ASSISTANT' ORDER BY message_order",
            String::class.java, chat.id)).containsOnly(model)
        assertThat(jdbc.queryForList("SELECT model_code FROM story_messages WHERE chat_id = ? AND role = 'USER' ORDER BY message_order",
            String::class.java, chat.id)).isNotEmpty().containsOnlyNulls()
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
        assertResponseModel(chat, "AURORA")
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
        assertResponseModel(chat, "AURORA")
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
        assertResponseModel(chat, "LITE")
    }

    @Test fun `라이트 회원 체험은 회원 공개 식별자를 보내고 차감 없이 체험을 소모한다`() {
        val user = member(exhaust = false)
        val chat = chat(user)
        service.changeSettings(chat.publicId.toString(), "LITE", user.id)
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("회원 체험", realtimeImage = false), user.id)
        waitForTurn(chat)
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("LITE")
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.userId).isEqualTo(user.publicId.toString())
        assertThat(wallet.balanceOf(user.id)).isEqualTo(1000)
        assertThat(trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isEqualTo(1)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id).last().creditTransactionId).isNull()
        assertResponseModel(chat, "LITE")
    }

    @Test fun `오로라 회원은 체험 잔여가 있어도 50을 차감하고 체험 카운터를 유지한다`() {
        val user = member(exhaust = false)
        val chat = chat(user)
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("오로라 유료", realtimeImage = false), user.id)
        waitForTurn(chat)
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("AURORA")
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.userId).isEqualTo(user.publicId.toString())
        assertThat(wallet.balanceOf(user.id)).isEqualTo(950)
        assertThat(trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isZero()
        val chargeId = messages.findByChatIdOrderByMessageOrderAsc(chat.id).last().creditTransactionId!!
        assertThat(transactions.findById(chargeId).orElseThrow().amount).isEqualTo(-50)
        assertResponseModel(chat, "AURORA")
    }

    @Test fun `오로라 회원은 체험 잔여와 잔액 49이면 402이고 체험 카운터를 유지한다`() {
        val user = member(exhaust = false, balance = 49)
        val chat = chat(user)
        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM, org.springframework.http.MediaType.APPLICATION_JSON)
            .body("""{"userInput":"잔액 부족", "realtimeImage":false}""")
            .exchange()
            .expectStatus().isEqualTo(402)
            .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_CREDIT")
        assertThat(wallet.balanceOf(user.id)).isEqualTo(49)
        assertThat(trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isZero()
        assertThat(GatedChatTurnAiClientConfig.lastRequest).isNull()
        assertThat(transactions.findAll().none { it.reason == CreditReason.CHAT_TURN }).isTrue()
    }

    @Test fun `오로라 재생성은 체험 잔여가 있어도 50을 차감하고 체험 카운터를 유지한다`() {
        val user = member(exhaust = false)
        val chat = chat(user)
        service.changeSettings(chat.publicId.toString(), "LITE", user.id)
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("재생성 준비", realtimeImage = false), user.id)
        waitForTurn(chat)
        await().atMost(Duration.ofSeconds(10)).until { service.getChatDetail(chat.publicId.toString(), user.id).generatingTurn == null }
        val first = messages.findByChatIdOrderByMessageOrderAsc(chat.id).last()
        assertResponseModel(chat, "LITE")
        service.changeSettings(chat.publicId.toString(), "AURORA", user.id)
        assertResponseModel(chat, "LITE")
        service.regenerateChatTurn(chat.publicId.toString(), RegenerateChatRequest(first.id, false), user.id)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(chats.findById(chat.id).orElseThrow().regeneratedCount).isEqualTo(1)
        }
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("AURORA")
        assertThat(wallet.balanceOf(user.id)).isEqualTo(950)
        assertThat(trials.usage(user.id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isEqualTo(1)
        val chargeId = messages.findById(first.id).orElseThrow().creditTransactionId!!
        assertThat(transactions.findById(chargeId).orElseThrow().amount).isEqualTo(-50)
        assertThat(versions.findAll().single().creditTransactionId).isNull()
        assertResponseModel(chat, "AURORA")
        assertThat(messages.findById(first.id).orElseThrow().createdAt).isEqualTo(first.createdAt)
    }

    @Test fun `게스트는 선택 모델을 보관하면서 라이트로 호출하고 user_id는 null이다`() {
        val chat = chat(null)
        service.streamChatTurn(chat.publicId.toString(), ContinueChatRequest("게스트 체험", realtimeImage = false), deviceId = "model-guest")
        waitForTurn(chat)
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.modelTier).isEqualTo("LITE")
        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.userId).isNull()
        assertThat(chats.findById(chat.id).orElseThrow().modelCode).isEqualTo("AURORA")
        assertThat(transactions.findAll()).isEmpty()
        assertResponseModel(chat, "LITE")
    }
}
