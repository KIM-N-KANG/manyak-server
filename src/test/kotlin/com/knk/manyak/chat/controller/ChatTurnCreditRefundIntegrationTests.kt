package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiException
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Duration

/**
 * 실패/미완료 턴의 크레딧 환불(KNK-399) 통합 검증.
 *
 * AI가 구조화 오류를 던져 completed 없이 error 이벤트로 끝나는 경로에서, 선차감한 CHAT_TURN이
 * 전액 환불(REFUND 행 추가)돼 순잔액이 원복되는지 확인한다. 예외를 던지는 가짜 AI 빈(@Primary)이
 * 별도 ApplicationContext를 만들므로, 기본 테스트와 create-drop이 간섭하지 않도록 H2를 분리한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-credit-refund;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class ChatTurnCreditRefundIntegrationTests {

    @TestConfiguration
    class ThrowingAiClientConfig {
        @Bean
        @Primary
        fun throwingChatTurnAiClient(): ChatTurnAiClient =
            object : ChatTurnAiClient {
                override fun streamTurn(
                    request: ChatTurnAiRequest,
                    onToken: (String) -> Unit,
                ): ChatTurnAiResult {
                    onToken("검")
                    throw ChatTurnAiException(code = "AI_TIMEOUT", message = "AI 응답이 시간 내에 도착하지 않았습니다.")
                }
            }
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

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `실패한 턴은 CHAT_TURN을 환불해 순잔액이 원복된다`() {
        val story = storyRepository.save(Story(title = "설정 미완 스토리", genre = "판타지"))
        val member = saveUser("환불회원")
        creditWalletService.reward(member.id, 10, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))

        val body = restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"손을 올린다."}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")

        // completed 없이 error로 종료한다.
        assertThat(body).contains("error")
        assertThat(body).contains("AI_TIMEOUT")
        assertThat(body).doesNotContain("completed")

        // 환불은 SSE 종료 콜백(비동기 워커)에서 일어나므로 순잔액 원복을 잠깐 기다린다.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(10)
        }
        // 원장에는 CHAT_TURN(-10)과 REFUND(+10)가 각각 정확히 1건씩 남는다(차감 1회·환불 1회).
        val all = transactionRepository.findAll()
        assertThat(all.count { it.reason == CreditReason.CHAT_TURN }).isEqualTo(1)
        val refund = all.filter { it.reason == CreditReason.REFUND }
        assertThat(refund).hasSize(1)
        assertThat(refund.first().amount).isEqualTo(10)
        assertThat(refund.first().refType).isEqualTo("CHAT")
        assertThat(refund.first().refId).isEqualTo(chat.id)
    }

    @Autowired
    private lateinit var b13GuestTrialLimitService: com.knk.manyak.credit.service.GuestTrialLimitService

    // B13(스펙 §4-3-7): 회원 체험을 먼저 소진해 크레딧 경로(선차감·환불)를 검증한다.
    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE)).also { member ->
            while (b13GuestTrialLimitService.reserveMember(member.id, com.knk.manyak.credit.service.GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        }
}
