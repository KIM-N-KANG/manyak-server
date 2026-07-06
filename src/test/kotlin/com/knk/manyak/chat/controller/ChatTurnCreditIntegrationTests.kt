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

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
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
            .jsonPath("$.code").isEqualTo("PAYMENT_REQUIRED")

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

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

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
