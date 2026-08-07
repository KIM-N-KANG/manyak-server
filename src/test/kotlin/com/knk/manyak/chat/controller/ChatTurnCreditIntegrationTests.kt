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
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 채팅 턴 크레딧 차감(KNK-399, 스펙 §4-3-7 소모)의 성공/차단 경로 통합 검증.
 *
 * 기본 스텁 AI([com.knk.manyak.chat.client.StubChatTurnAiClient])는 completed까지 정상 종료하므로
 * 여기서는 (1) 회원이면 CHAT_TURN 1건이 차감되고, (2) 잔액 부족이면 SSE를 열기 전에 동기 402가 나며
 * 아무것도 차감되지 않고, (3) 게스트는 차감되지 않고, (4) 정상 완료 턴은 환불되지 않음을 검증한다.
 * 실패 턴의 환불은 예외를 던지는 가짜 AI가 필요하므로 [ChatTurnCreditRefundIntegrationTests]에서 다룬다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatTurnCreditIntegrationTests {

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

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `정지된 회원은 채팅 턴 진행이 403이고 크레딧이 차감되지 않는다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val suspended = userRepository.save(User(nickname = "정지회원", status = UserStatus.SUSPENDED))
        creditWalletService.reward(suspended.id, 100, CreditReason.SIGNUP_REWARD, "signup:${suspended.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = suspended.id))

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", authHeaderFor(suspended))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"손을 올린다."}""")
            .exchange()
            .expectStatus().isForbidden

        assertThat(creditWalletService.balanceOf(suspended.id)).isEqualTo(100)
        assertThat(transactionRepository.findAll().none { it.reason == CreditReason.CHAT_TURN }).isTrue()
    }

    @Test
    fun `회원이 충분한 잔액으로 이어쓰면 턴이 진행되고 CHAT_TURN 10이 차감된다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("차감회원")
        creditWalletService.reward(member.id, 100, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))

        val body = streamAsMember(chat.publicId.toString(), member, "마법수정에 손을 올린다.")

        // 턴 정상 진행: completed 이벤트 도달
        assertThat(body).contains("completed")
        // 잔액 10 차감(100 → 90)
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(90)
        // CHAT_TURN 소모 원장 1건: 음수 amount, CHAT 참조
        val consumption = transactionRepository.findAll().first { it.reason == CreditReason.CHAT_TURN }
        assertThat(consumption.amount).isEqualTo(-10)
        assertThat(consumption.refType).isEqualTo("CHAT")
        assertThat(consumption.refId).isEqualTo(chat.id)
    }

    @Test
    fun `정상 완료된 턴은 환불되지 않는다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("완료회원")
        creditWalletService.reward(member.id, 100, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))

        streamAsMember(chat.publicId.toString(), member, "앞으로 나선다.")

        // 정상 완료: CHAT_TURN 1건만 있고 REFUND 행은 없다. 잔액은 90으로 유지된다.
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(90)
        assertThat(transactionRepository.findAll().count { it.reason == CreditReason.CHAT_TURN }).isEqualTo(1)
        assertThat(transactionRepository.findAll().none { it.reason == CreditReason.REFUND }).isTrue()
    }

    // --- KNK-811: 채팅 턴 결과 분포(manyak.chat.turn.result) ---
    // 채팅은 지금까지 AI 경계(manyak.ai.call.duration)만 덮여 있었다. 크레딧·게스트 한도 거부는 SseEmitter를
    // 열기 전 동기 구간에서 끊기므로 AI 타이머에도 Langfuse에도 안 남는다(스토리라인과 같은 사각지대).
    // 한쪽 카운터만 단정하면 오분류를 못 잡으므로 늘어야 할 값과 늘지 않아야 할 값을 함께 본다.

    @Test
    fun `턴이 저장까지 끝나면 chat_turn_result의 success를 올리고 failure·rejected는 올리지 않는다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("성공지표회원")
        creditWalletService.reward(member.id, 100, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))
        // @SpringBootTest 컨텍스트는 클래스 간 캐시 공유라 카운터가 누적된다. 절대값이 아니라 증가분을 본다.
        val beforeSuccess = chatTurnResultCount("success")
        val beforeFailure = chatTurnResultCount("failure")
        val beforeRejected = chatTurnResultCount("rejected")

        assertThat(streamAsMember(chat.publicId.toString(), member, "문을 연다.")).contains("completed")

        // 결과 기록은 SSE 워커의 finally에서 일어나므로 잠깐 기다린다.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(chatTurnResultCount("success")).isEqualTo(beforeSuccess + 1)
        }
        assertThat(chatTurnResultCount("failure")).isEqualTo(beforeFailure)
        assertThat(chatTurnResultCount("rejected")).isEqualTo(beforeRejected)
    }

    @Test
    fun `잔액 부족으로 스트림 개시 전 402면 rejected를 올리고 success·failure는 올리지 않는다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("거부지표회원")
        // 잔액 0 → 선차감 단계에서 InsufficientCreditException → 컨트롤러가 402. AI를 부르지 않는다.
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))
        val beforeRejected = chatTurnResultCount("rejected")
        val beforeSuccess = chatTurnResultCount("success")
        val beforeFailure = chatTurnResultCount("failure")

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"손을 든다."}""")
            .exchange()
            .expectStatus().isEqualTo(402)

        assertThat(chatTurnResultCount("rejected")).isEqualTo(beforeRejected + 1)
        assertThat(chatTurnResultCount("success")).isEqualTo(beforeSuccess)
        assertThat(chatTurnResultCount("failure")).isEqualTo(beforeFailure)
    }

    private fun chatTurnResultCount(outcome: String): Double =
        meterRegistry.find("manyak.chat.turn.result").tag("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `회원이 체험 잔여가 있으면 크레딧 대신 체험으로 무료 처리된다`() {
        // B13(스펙 §4-3-7): 회원 소모 2단(체험 잔여 → 크레딧). saveUser는 체험을 소진시키므로,
        // 체험 잔여가 있는 신규 회원은 직접 생성해 체험 우선 소진을 검증한다.
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = userRepository.save(User(nickname = "체험회원", status = UserStatus.ACTIVE))
        creditWalletService.reward(member.id, 100, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))

        val body = streamAsMember(chat.publicId.toString(), member, "체험으로 진행한다.")

        assertThat(body).contains("completed")
        // 크레딧은 차감되지 않고(잔액 유지), CHAT_TURN 소모 원장도 남지 않는다(체험 잔여로 무료 처리).
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(100)
        assertThat(transactionRepository.findAll().none { it.reason == CreditReason.CHAT_TURN }).isTrue()
    }

    @Test
    fun `회원 잔액이 부족하면 SSE를 열기 전에 동기 402로 응답하고 아무것도 차감되지 않는다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("빈지갑회원")
        // 지갑 잔액 0(적립 없음).
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id))

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", authHeaderFor(member))
            .contentType(MediaType.APPLICATION_JSON)
            // SSE와 JSON을 모두 수용해, 스트림 대신 동기 402 JSON이 오는지 확인한다.
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"손을 올린다."}""")
            .exchange()
            .expectStatus().isEqualTo(402)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.status").isEqualTo(402)
            // 크레딧 부족은 게스트 한도와 구분되는 code로 온다(KNK-524).
            .jsonPath("$.code").isEqualTo("INSUFFICIENT_CREDIT")

        // 스트림이 열리지 않았으므로 턴이 저장되지 않고, 소모 원장도 남지 않는다.
        val reloaded = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloaded.currentTurn).isZero()
        assertThat(transactionRepository.findAll()).isEmpty()
    }

    @Test
    fun `게스트는 차감되지 않고 턴이 정상 진행된다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))

        val body = restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"조용히 주변을 살핀다."}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")

        assertThat(body).contains("completed")
        // 게스트는 어떤 크레딧 원장도 남기지 않는다.
        assertThat(transactionRepository.findAll()).isEmpty()
    }

    @Test
    fun `회원 소유 채팅을 토큰 없이 이어쓰면 403이고 아무것도 저장·차감되지 않는다`() {
        // 우회 차단(스펙 §4-5): owned 채팅에 토큰을 빼고(게스트로 위장) 이어써 무료 턴을 얻으려는 시도를 막는다.
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val owner = saveUser("소유회원")
        creditWalletService.reward(owner.id, 10, CreditReason.SIGNUP_REWARD, "signup:${owner.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = owner.id))

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            // Authorization 헤더 없음 → @CurrentUserId == null.
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"몰래 이어쓴다."}""")
            .exchange()
            .expectStatus().isForbidden

        // 스트림이 열리지 않아 턴이 저장되지 않고, 소유자 지갑도 차감되지 않는다.
        val reloaded = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloaded.currentTurn).isZero()
        assertThat(creditWalletService.balanceOf(owner.id)).isEqualTo(10)
        assertThat(transactionRepository.findAll().none { it.reason == CreditReason.CHAT_TURN }).isTrue()
    }

    @Test
    fun `다른 회원의 소유 채팅을 이어쓰면 403이고 어느 쪽도 차감되지 않는다`() {
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val owner = saveUser("소유자A")
        val intruder = saveUser("침입자B")
        creditWalletService.reward(owner.id, 10, CreditReason.SIGNUP_REWARD, "signup:${owner.id}")
        creditWalletService.reward(intruder.id, 10, CreditReason.SIGNUP_REWARD, "signup:${intruder.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = owner.id))

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("Authorization", authHeaderFor(intruder))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"남의 채팅을 이어쓴다."}""")
            .exchange()
            .expectStatus().isForbidden

        // 소유자·침입자 어느 쪽도 차감되지 않고 턴도 저장되지 않는다.
        val reloaded = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloaded.currentTurn).isZero()
        assertThat(creditWalletService.balanceOf(owner.id)).isEqualTo(10)
        assertThat(creditWalletService.balanceOf(intruder.id)).isEqualTo(10)
        assertThat(transactionRepository.findAll().none { it.reason == CreditReason.CHAT_TURN }).isTrue()
    }

    @Test
    fun `회원이 게스트(NULL) 소유 채팅을 이어쓰면 403이고 아무것도 진행·차감되지 않는다`() {
        // 교차 접근 차단(§4-5, KNK-480): 인증 회원은 게스트가 만든 NULL 소유 채팅에 이어쓸 수 없다(이관 후 접근).
        val story = storyRepository.save(Story(title = "크레딧 스토리", genre = "판타지"))
        val member = saveUser("회원")
        creditWalletService.reward(member.id, 100, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val guestChat = storyChatRepository.save(StoryChat(storyId = story.id))

        restTestClient.post()
            .uri("/api/v1/chats/${guestChat.publicId}/turns/stream")
            .header("Authorization", authHeaderFor(member))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"남의 게스트 채팅을 이어쓴다."}""")
            .exchange()
            .expectStatus().isForbidden

        val reloaded = storyChatRepository.findById(guestChat.id).orElseThrow()
        assertThat(reloaded.currentTurn).isZero()
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(100)
    }

    @Autowired
    private lateinit var b13GuestTrialLimitService: com.knk.manyak.credit.service.GuestTrialLimitService

    // B13(스펙 §4-3-7): 회원도 잔여 체험을 크레딧보다 먼저 소진한다. 크레딧 경로를 검증하려면 회원 체험을 미리 소진시킨다.
    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE)).also { member ->
            while (b13GuestTrialLimitService.reserveMember(member.id, com.knk.manyak.credit.service.GuestTrialLimitService.Counter.CHAT_TURN)) { /* drain */ }
        }

    private fun authHeaderFor(user: User): String =
        "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    private fun streamAsMember(chatId: String, member: User, userInput: String): String =
        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/stream")
            .header("Authorization", authHeaderFor(member))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"$userInput"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")
}
