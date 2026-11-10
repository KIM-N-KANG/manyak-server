package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.ScriptedAiIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 저장된 턴은 환불하지 않는다(KNK-399, Codex P1) 통합 검증.
 *
 * 판정 기준은 completed 전송이 아니라 "저장 성공(persistTurn 반환)"이다. 환불은 워커의 finally 단일 판정으로
 * 일원화됐고(onCompletion은 환불하지 않음), 그 판정은 !persisted에만 환불하므로 저장된 턴에는 환불 경로 자체가
 * 없다. 이 테스트가 그 불변식을 지킨다: 저장 직후의 종료 단계(attachTurnNumber)가 던져 completed까지 못 가도
 * (타임아웃 뒤 워커가 뒤늦게 저장하는 경합과 같은 코드 경로 — 워커가 persisted를 세운 뒤 종료), 선차감분이
 * 환불되지 않고(REFUND 없음) 차감이 유지되며 턴이 이력에 저장돼 있는지 확인한다.
 *
 * (실제 클라이언트의 post-persist 연결 끊김·60s 타임아웃은 RestTestClient로 재현할 수 없으므로,
 * persistTurn 성공 직후의 종료 단계가 던지도록 AiCallRecorder를 대체해 결정적으로 만든다.)
 *
 * (AiCallRecorder는 공용 스파이다. record 등은 실제 동작을 그대로 두고 attachTurnNumber만 던지게 스텁한다.
 * 스텁은 테스트마다 자동 reset된다.)
 */
class ChatTurnCreditPersistedNoRefundIntegrationTests : ScriptedAiIntegrationTest() {
    @Autowired private lateinit var chatModelService: com.knk.manyak.chat.service.ChatModelService
    @Autowired private lateinit var chatModelRepository: com.knk.manyak.chat.repository.ChatModelRepository


    /** persistTurn 성공 직후 호출되는 attachTurnNumber를 던지게 해, 저장은 됐지만 completed 전까지 실패한 상태를 만든다. */
    @Autowired private lateinit var aiCallRecorder: AiCallRecorder

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

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
        Mockito.doThrow(RuntimeException("post-persist terminal failure (test)"))
            .`when`(aiCallRecorder).attachTurnNumber(Mockito.anyLong(), Mockito.anyInt())
    }

    @Test
    fun `저장된 턴은 종료 단계가 실패해도 환불되지 않고 차감이 유지된다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("저장확정회원")
        creditWalletService.reward(member.id, seedBalance, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))

        // 스텁 AI는 정상 반환 → persistTurn이 실제로 저장(차감 확정) → 직후 attachTurnNumber가 던져 completed 미도달.
        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"마법수정에 손을 올린다."}""")
            .exchange()
            .expectStatus().isOk

        // 턴은 실제로 저장됐다(USER+ASSISTANT, current_turn 1).
        val reloaded = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloaded.currentTurn).isEqualTo(1)
        assertThat(storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id)).hasSize(2)

        // 저장된 턴이므로 환불하지 않는다: CHAT_TURN(-10) 1건만 있고 REFUND는 없다. 잔액은 90으로 유지된다.
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(seedBalance - chatTurnCost)
        val all = transactionRepository.findAll()
        assertThat(all.count { it.reason == CreditReason.CHAT_TURN }).isEqualTo(1)
        assertThat(all.none { it.reason == CreditReason.REFUND }).isTrue()
    }

    @Autowired
    private lateinit var b13GuestTrialLimitService: com.knk.manyak.credit.service.GuestTrialLimitService

    // B13(스펙 §4-3-7): 회원 체험을 먼저 소진해 크레딧 경로(선차감·환불 없음)를 검증한다.
    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE)).also { member ->
            while (b13GuestTrialLimitService.reserveMember(member.id, com.knk.manyak.credit.service.GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        }
}
