package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.client.ChatCharacterImageEvent
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatChoicesResult
import com.knk.manyak.chat.client.ChatTurnAiException
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditPolicyRepository
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import io.micrometer.core.instrument.MeterRegistry
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
                override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String, traceLink: AiTraceLink): ChatChoicesResult = ChatChoicesResult(emptyList())
                override fun streamTurn(
                    request: ChatTurnAiRequest,
                    traceLink: AiTraceLink,
                    onCharacterImage: (ChatCharacterImageEvent) -> Unit,
                    onToken: (String) -> Unit,
                ): ChatTurnAiResult {
                    // 선차감은 이 호출 전에 이미 끝났다. 차감과 환불 사이에 무언가를 끼워 넣는 유일한 지점이다.
                    afterCharge()
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

    // 레지스트리가 여럿이면(prometheus·otlp 동시 활성) CompositeMeterRegistry가 @Primary라 인터페이스로 받는다.
    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired private lateinit var creditPolicyService: CreditPolicyService

    @Autowired private lateinit var creditPolicyRepository: CreditPolicyRepository

    // 수치는 팀이 조정하는 정책값이라 리터럴로 박지 않고 해석 결과를 그대로 쓴다(KNK-1056).
    private val chatTurnCost: Long get() = creditPolicyService.amountOf(CreditPolicyKey.CHAT_TURN_COST)

    // 여러 턴을 견디는 충분한 잔액.
    private val seedBalance: Long get() = chatTurnCost * 10

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        // 공유 컨텍스트의 정책 스냅샷을 빈 테이블 상태로 맞춘다(앞 테스트의 오버라이드가 남지 않게).
        creditPolicyService.refresh()
        afterCharge = {}
    }

    companion object {
        /**
         * 선차감 직후·환불 직전에 실행되는 테스트 훅. AI 스텁 빈(@Primary)이 호출한다.
         * 스텁은 SSE 워커 스레드에서 도므로 @Volatile 이어야 한다. 새 @TestConfiguration 을 만들지 않으려고
         * 정적 훅을 쓴다(중첩 설정 하나가 스프링 컨텍스트 하나다 — SpringContextBudgetGuardTests).
         */
        @Volatile
        @JvmStatic
        var afterCharge: () -> Unit = {}
    }

    @Test
    fun `AI 실패로 저장 없이 끝난 턴은 failure와 환불 success를 함께 올린다`() {
        // KNK-811. 저장 전 실패라 선차감분이 환불된다. 환불 실패는 지금까지 로그(chat_turn_refund_failed)에만
        // 남아, 사용자가 실패한 턴에 과금된 채로 남는 상황을 지표로 볼 수 없었다.
        val story = storyRepository.save(Story(title = "지표 스토리", genre = "판타지"))
        val member = saveUser("실패지표회원")
        creditWalletService.reward(member.id, chatTurnCost, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))
        // @SpringBootTest 컨텍스트는 클래스 간 캐시 공유라 카운터가 누적된다. 절대값이 아니라 증가분을 본다.
        val beforeFailure = chatTurnResultCount("failure")
        val beforeSuccess = chatTurnResultCount("success")
        val beforeRefundOk = chatTurnRefundCount("success")

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"불을 붙인다."}""")
            .exchange()
            .expectStatus().isOk

        // 결과·환불 기록 모두 SSE 워커의 finally에서 일어나므로 잠깐 기다린다.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(chatTurnResultCount("failure")).isEqualTo(beforeFailure + 1)
            assertThat(chatTurnRefundCount("success")).isEqualTo(beforeRefundOk + 1)
        }
        // 스트림은 200으로 열렸지만 저장에 도달하지 못했으므로 success는 오르지 않아야 한다.
        assertThat(chatTurnResultCount("success")).isEqualTo(beforeSuccess)
    }

    private fun chatTurnResultCount(outcome: String): Double =
        meterRegistry.find("manyak.chat.turn.result").tag("outcome", outcome).counter()?.count() ?: 0.0

    private fun chatTurnRefundCount(outcome: String): Double =
        meterRegistry.find("manyak.chat.turn.refund").tag("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `환불 실패 카운터는 실패가 한 번도 없어도 등록돼 있다`() {
        // KNK-800. 알림이 "10분간 failure > 0"으로 첫 한 건을 잡으려 하는데, Counter가 첫 increment 때
        // 등록되면 첫 실패는 값 1짜리 샘플 하나로 등장한다. increase()는 뺄 이전 샘플이 없어 결과를 내지
        // 못하고 규칙은 No Data(=Normal)로 삼켜진다 — 잡으려던 바로 그 한 건을 놓친다.
        // ChatTurnRefundMeters가 기동 시 0으로 등록해 두면 첫 실패가 0->1 증가로 잡힌다.
        // 카운터는 클래스 간 공유 컨텍스트에서 누적될 수 있으므로 값이 아니라 존재만 본다.
        assertThat(meterRegistry.find("manyak.chat.turn.refund").tag("outcome", "failure").counter())
            .describedAs("환불 failure 카운터가 기동 시점부터 등록돼 있어야 한다")
            .isNotNull
    }

    @Test
    fun `실패한 턴은 CHAT_TURN을 환불해 순잔액이 원복된다`() {
        val story = storyRepository.save(Story(title = "설정 미완 스토리", genre = "판타지"))
        val member = saveUser("환불회원")
        creditWalletService.reward(member.id, chatTurnCost, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
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
            assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(chatTurnCost)
        }
        // 원장에는 CHAT_TURN(-10)과 REFUND(+10)가 각각 정확히 1건씩 남는다(차감 1회·환불 1회).
        val all = transactionRepository.findAll()
        assertThat(all.count { it.reason == CreditReason.CHAT_TURN }).isEqualTo(1)
        val refund = all.filter { it.reason == CreditReason.REFUND }
        assertThat(refund).hasSize(1)
        assertThat(refund.first().amount).isEqualTo(chatTurnCost)
        assertThat(refund.first().refType).isEqualTo("CHAT")
        assertThat(refund.first().refId).isEqualTo(chat.id)
    }

    @Test
    fun `차감 후 정책이 바뀌어도 환불액은 원 차감액과 같다`() {
        // KNK-1056 의 핵심 불변식. 차감액과 환불액은 반드시 같아야 한다 — 어긋나면 사용자가 손해를 보거나
        // 이득을 본다. 소비자는 요청 진입부에서 정책을 한 번만 읽어 차감·환불에 같은 값을 넘겨야 하고,
        // 환불 시점에 다시 읽으면 이 테스트가 깨진다.
        // 읽기는 메모리라 저장 후 적재를 유발해야 반영된다(운영에선 스케줄러 몫).
        creditPolicyRepository.save(CreditPolicy(policyKey = CreditPolicyKey.CHAT_TURN_COST.storageKey, amount = 41))
        creditPolicyService.refresh()
        val story = storyRepository.save(Story(title = "정책 변경 스토리", genre = "판타지"))
        val member = saveUser("정책변경회원")
        creditWalletService.reward(member.id, 500, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))
        // 차감이 끝난 뒤, 환불이 일어나기 전에 단가를 바꾼다.
        afterCharge = {
            creditPolicyRepository.save(CreditPolicy(policyKey = CreditPolicyKey.CHAT_TURN_COST.storageKey, amount = 7))
            // 적재까지 해 둔다 — 환불이 그 시점 정책을 다시 읽는 구현이면 7 을 보고 금액이 어긋나야 red 가 된다.
            creditPolicyService.refresh()
        }

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"정책이 바뀌는 동안 이어쓴다."}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()

        // 순잔액이 정확히 원복돼야 한다. 환불이 바뀐 단가(7)를 쓰면 34 가 빈다.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(500)
        }
        val all = transactionRepository.findAll()
        assertThat(all.first { it.reason == CreditReason.CHAT_TURN }.amount).isEqualTo(-41)
        assertThat(all.first { it.reason == CreditReason.REFUND }.amount).isEqualTo(41)
        // 정책 자체는 실제로 바뀌어 있어야 한다(훅이 안 돌았는데 통과하는 위양성 방지).
        assertThat(creditPolicyService.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(7)
    }

    @Autowired
    private lateinit var b13GuestTrialLimitService: com.knk.manyak.credit.service.GuestTrialLimitService

    // B13(스펙 §4-3-7): 회원 체험을 먼저 소진해 크레딧 경로(선차감·환불)를 검증한다.
    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE)).also { member ->
            while (b13GuestTrialLimitService.reserveMember(member.id, com.knk.manyak.credit.service.GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        }
}
