package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.ScriptedAiIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.beans.factory.annotation.Qualifier
import org.mockito.Mockito
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * SSE 작업 스케줄 거부 시 선차감 환불(KNK-399, Codex P1) 통합 검증.
 *
 * chatSseExecutor가 포화로 제출을 거부하면(RejectedExecutionException) 비동기 블록의 catch·onCompletion
 * 환불이 돌지 않는다. 이때 선차감분이 환불돼 순잔액이 원복되는지 확인한다. 공용 스파이 executor가
 * 항상 거부하도록 스텁한다(스텁은 테스트마다 자동 reset된다).
 */
class ChatTurnCreditScheduleRejectionIntegrationTests : ScriptedAiIntegrationTest() {
    @Autowired private lateinit var chatModelService: com.knk.manyak.chat.service.ChatModelService
    @Autowired private lateinit var chatModelRepository: com.knk.manyak.chat.repository.ChatModelRepository


    // 서비스가 @Qualifier("chatSseExecutor")로 주입하는 공용 스파이다. execute마다 즉시 거부해
    // CompletableFuture.runAsync 제출 지점에서 RejectedExecutionException이 동기로 던져지게 한다.
    @Autowired @Qualifier("chatSseExecutor") private lateinit var chatSseExecutor: Executor

    @BeforeEach
    fun rejectChatSseExecutor() {
        Mockito.doThrow(RejectedExecutionException("executor saturated (test)"))
            .`when`(chatSseExecutor).execute(Mockito.any(Runnable::class.java))
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var creditWalletService: CreditWalletService

    @Autowired
    private lateinit var transactionRepository: CreditTransactionRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @Autowired private lateinit var creditPolicyService: CreditPolicyService

    // 수치는 팀이 조정하는 정책값이라 리터럴로 박지 않고 해석 결과를 그대로 쓴다(KNK-1056).
    private val chatTurnCost: Long get() = chatModelService.litePrice()

    // 여러 턴을 견디는 충분한 잔액.
    private val seedBalance: Long get() = chatTurnCost * 10

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `스케줄 거부 시 선차감분이 환불돼 순잔액이 원복된다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("스케줄거부회원")
        creditWalletService.reward(member.id, chatTurnCost, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))

        // 제출 거부는 SseEmitter 반환 전에 동기로 던져지므로, 스트림 200이 아니라 오류 상태로 응답한다.
        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"손을 올린다."}""")
            .exchange()
            .expectStatus().is5xxServerError

        // 선차감(-10)과 환불(+10)이 남아 순잔액은 원복되고, 턴은 저장되지 않는다.
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(chatTurnCost)
        val all = transactionRepository.findAll()
        assertThat(all.count { it.reason == CreditReason.CHAT_TURN }).isEqualTo(1)
        val refund = all.filter { it.reason == CreditReason.REFUND }
        assertThat(refund).hasSize(1)
        assertThat(refund.first().amount).isEqualTo(chatTurnCost)
        assertThat(refund.first().refType).isEqualTo("CHAT_TURN")
        assertThat(refund.first().refId).isEqualTo(chat.id)
        val reloaded = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloaded.currentTurn).isZero()
    }

    @Autowired
    private lateinit var b13GuestTrialLimitService: com.knk.manyak.credit.service.GuestTrialLimitService

    // B13(스펙 §4-3-7): 회원 체험을 먼저 소진해 크레딧 경로(스케줄 거부 시 환불)를 검증한다.
    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE)).also { member ->
            while (b13GuestTrialLimitService.reserveMember(member.id, com.knk.manyak.credit.service.GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        }
}
