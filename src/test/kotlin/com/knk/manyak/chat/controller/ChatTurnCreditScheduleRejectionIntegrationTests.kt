package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
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
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * SSE 작업 스케줄 거부 시 선차감 환불(KNK-399, Codex P1) 통합 검증.
 *
 * chatSseExecutor가 포화로 제출을 거부하면(RejectedExecutionException) 비동기 블록의 catch·onCompletion
 * 환불이 돌지 않는다. 이때 선차감분이 환불돼 순잔액이 원복되는지 확인한다. 항상 거부하는 가짜 executor(@Primary)를
 * 주입하며, 별도 ApplicationContext의 create-drop이 간섭하지 않도록 H2를 분리한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-credit-reject;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        // 같은 이름(chatSseExecutor) 빈을 테스트용 거부 executor로 대체하려면 override를 허용해야 한다(기본은 금지).
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
class ChatTurnCreditScheduleRejectionIntegrationTests {

    @TestConfiguration
    class RejectingExecutorConfig {
        // 서비스가 @Qualifier("chatSseExecutor")로 주입하므로 이름을 맞춰 대체한다. execute마다 즉시 거부해
        // CompletableFuture.runAsync 제출 지점에서 RejectedExecutionException이 동기로 던져지게 한다.
        @Bean(name = ["chatSseExecutor"])
        @Primary
        fun rejectingChatSseExecutor(): Executor =
            Executor { throw RejectedExecutionException("executor saturated (test)") }
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
    fun `스케줄 거부 시 선차감분이 환불돼 순잔액이 원복된다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("스케줄거부회원")
        creditWalletService.reward(member.id, 10, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
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

        // 선차감(-1)과 환불(+1)이 남아 순잔액은 원복되고, 턴은 저장되지 않는다.
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(10)
        val all = transactionRepository.findAll()
        assertThat(all.count { it.reason == CreditReason.CHAT_TURN }).isEqualTo(1)
        val refund = all.filter { it.reason == CreditReason.REFUND }
        assertThat(refund).hasSize(1)
        assertThat(refund.first().amount).isEqualTo(1)
        assertThat(refund.first().refType).isEqualTo("CHAT")
        assertThat(refund.first().refId).isEqualTo(chat.id)
        val reloaded = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloaded.currentTurn).isZero()
    }

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))
}
