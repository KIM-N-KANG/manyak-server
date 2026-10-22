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
        service.changeModel(chat.publicId.toString(), "LITE", user.id)
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
