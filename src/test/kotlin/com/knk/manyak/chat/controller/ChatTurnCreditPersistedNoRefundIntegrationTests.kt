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
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.global.observability.aicall.AiCallLogRepository
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
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
 * (kotlin-spring 플러그인이 @Component를 open으로 열어 AiCallRecorder를 서브클래싱할 수 있다. record 등은
 * 실제 동작을 그대로 상속하고 attachTurnNumber만 던지게 오버라이드한다. @Primary 빈으로 별도 컨텍스트가
 * 생기므로 create-drop 간섭을 막으려 H2를 분리한다.)
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-credit-persisted;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class ChatTurnCreditPersistedNoRefundIntegrationTests {

    /** persistTurn 성공 직후 호출되는 attachTurnNumber를 던지게 해, 저장은 됐지만 completed 전까지 실패한 상태를 만든다. */
    class FailingAfterPersistAiCallRecorder(
        repository: AiCallLogRepository,
        callerService: String,
    ) : AiCallRecorder(repository, callerService) {
        override fun attachTurnNumber(aiCallLogId: Long, turnNumber: Int) {
            throw RuntimeException("post-persist terminal failure (test)")
        }
    }

    @TestConfiguration
    class FailingRecorderConfig {
        @Bean
        @Primary
        fun failingAiCallRecorder(
            repository: AiCallLogRepository,
            @Value("\${spring.application.name:manyak-server}") callerService: String,
        ): AiCallRecorder = FailingAfterPersistAiCallRecorder(repository, callerService)
    }

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

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `저장된 턴은 종료 단계가 실패해도 환불되지 않고 차감이 유지된다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("저장확정회원")
        creditWalletService.reward(member.id, 10, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
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

        // 저장된 턴이므로 환불하지 않는다: CHAT_TURN(-1) 1건만 있고 REFUND는 없다. 잔액은 9로 유지된다.
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(9)
        val all = transactionRepository.findAll()
        assertThat(all.count { it.reason == CreditReason.CHAT_TURN }).isEqualTo(1)
        assertThat(all.none { it.reason == CreditReason.REFUND }).isTrue()
    }

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))
}
