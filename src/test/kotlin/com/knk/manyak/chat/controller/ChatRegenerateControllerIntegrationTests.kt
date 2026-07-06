package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.ChatStatus
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
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
 * AI 응답 재생성 스트리밍(KNK-406, 스펙 §4-3-9) 통합 검증.
 *
 * 마지막 턴의 AI 출력·선택지가 같은 사용자 입력으로 다시 생성돼 제자리 교체되고, turn_number·사용자 입력은
 * 불변인지 확인한다. 낡은 turnId 409, 턴 0개 404, 엔딩 도달 409, 그리고 회원 크레딧 선차감/무차감을 검증한다.
 * AI는 기본 스텁(manyak.ai.chat.stub)을 사용하며, 스텁은 마지막 사용자 입력을 본문에 에코한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatRegenerateControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var storyChoiceRepository: StoryChoiceRepository

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
    fun `재생성은 마지막 턴 AI 출력·선택지만 교체하고 사용자 입력·턴 수는 불변이다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 2))
        // 2턴이 쌓인 상태: order 1~4. 마지막 턴 = order3 USER + order4 ASSISTANT.
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "첫 입력", messageOrder = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "첫 응답", messageOrder = 2))
        val lastUser = storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "마지막 입력", messageOrder = 3))
        val lastAssistant = storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "원본 마지막 응답", messageOrder = 4))
        // 원본 선택지 2개(교체돼야 함)
        storyChoiceRepository.save(StoryChoice(chatId = chat.id, messageId = lastAssistant.id, choiceText = "원본 선택 A", choiceOrder = 1))
        storyChoiceRepository.save(StoryChoice(chatId = chat.id, messageId = lastAssistant.id, choiceText = "원본 선택 B", choiceOrder = 2))

        val body = regenerate(chat.publicId.toString(), lastAssistant.id)

        // 이벤트 순서: started → token → completed
        val startedAt = body.indexOf("started")
        val tokenAt = body.indexOf("token")
        val completedAt = body.indexOf("completed")
        assertThat(startedAt).isGreaterThanOrEqualTo(0)
        assertThat(tokenAt).isGreaterThan(startedAt)
        assertThat(completedAt).isGreaterThan(tokenAt)
        // completed의 turnId는 제자리 교체이므로 기존 ASSISTANT id 그대로다.
        assertThat(body).contains("\"turnId\":${lastAssistant.id}")

        // 메시지는 여전히 4건, id·순서 불변. 마지막 ASSISTANT 본문만 재생성된 값으로 바뀐다.
        val messages = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id)
        assertThat(messages).hasSize(4)
        val reloadedLastAssistant = messages[3]
        assertThat(reloadedLastAssistant.id).isEqualTo(lastAssistant.id)
        assertThat(reloadedLastAssistant.role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(reloadedLastAssistant.content).isNotEqualTo("원본 마지막 응답")
        // 스텁은 마지막 사용자 입력을 본문에 에코한다.
        assertThat(reloadedLastAssistant.content).contains("마지막 입력")

        // 마지막 USER 입력과 앞선 턴은 불변(같은 id·본문).
        assertThat(messages[2].id).isEqualTo(lastUser.id)
        assertThat(messages[2].role).isEqualTo(MessageRole.USER)
        assertThat(messages[2].content).isEqualTo("마지막 입력")
        assertThat(messages[0].content).isEqualTo("첫 입력")
        assertThat(messages[1].content).isEqualTo("첫 응답")

        // 선택지는 전체 교체(원본 2개 → 스텁 새 선택지). 원본 텍스트는 남지 않는다.
        val choices = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(lastAssistant.id)
        assertThat(choices).isNotEmpty
        assertThat(choices.map { it.choiceText }).doesNotContain("원본 선택 A", "원본 선택 B")
        assertThat(choices.map { it.choiceOrder.toInt() }).startsWith(1)

        // turn_number(current_turn)는 증가하지 않는다.
        val reloadedChat = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloadedChat.currentTurn).isEqualTo(2)
    }

    @Test
    fun `낡은 turnId로 재생성하면 409로 거절하고 아무것도 바꾸지 않는다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val lastAssistant = storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "원본 응답", messageOrder = 2))

        // 서버의 마지막 턴 id보다 낮은(낡은) 값
        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/regenerate/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"turnId":${lastAssistant.id - 1}}""")
            .exchange()
            .expectStatus().isEqualTo(409)

        // 마지막 응답은 그대로 유지된다.
        val reloaded = storyMessageRepository.findFirstByChatIdOrderByMessageOrderDesc(chat.id)!!
        assertThat(reloaded.content).isEqualTo("원본 응답")
    }

    @Test
    fun `턴이 0개인 채팅을 재생성하면 404로 응답한다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/regenerate/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"turnId":1}""")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `순차 정수 ID로 재생성하면 404로 응답한다`() {
        // IDOR 방지: 순차 정수는 공개 식별자가 아니다.
        restTestClient.post()
            .uri("/api/v1/chats/999999/turns/regenerate/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"turnId":1}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
    }

    @Test
    fun `엔딩에 도달한 채팅을 재생성하면 409로 응답한다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1, status = ChatStatus.ENDED))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val lastAssistant = storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "엔딩 응답", messageOrder = 2))

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/regenerate/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"turnId":${lastAssistant.id}}""")
            .exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `turnId가 비양수면 400으로 응답한다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "응답", messageOrder = 2))

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/regenerate/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"turnId":0}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `회원 소유 채팅 재생성은 1턴분을 선차감하고 성공 시 환불하지 않는다`() {
        val story = seedStory()
        val member = saveUser("재생성회원")
        creditWalletService.reward(member.id, 10, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "마지막 입력", messageOrder = 1))
        val lastAssistant = storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "원본 응답", messageOrder = 2))

        val body = restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/regenerate/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"turnId":${lastAssistant.id}}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")

        assertThat(body).contains("completed")
        // 성공 저장이므로 CHAT_TURN(-1)만 남고 REFUND는 없다 → 순잔액 9.
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(9)
        val all = transactionRepository.findAll()
        assertThat(all.count { it.reason == CreditReason.CHAT_TURN }).isEqualTo(1)
        assertThat(all.count { it.reason == CreditReason.REFUND }).isZero()
    }

    @Test
    fun `낡은 turnId 재생성은 선차감 전에 거절되어 크레딧을 소모하지 않는다`() {
        val story = seedStory()
        val member = saveUser("무차감회원")
        creditWalletService.reward(member.id, 10, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val lastAssistant = storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "원본 응답", messageOrder = 2))

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/regenerate/stream")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"turnId":${lastAssistant.id - 1}}""")
            .exchange()
            .expectStatus().isEqualTo(409)

        // 검증(409)은 선차감 전이므로 잔액·원장 모두 그대로다.
        assertThat(creditWalletService.balanceOf(member.id)).isEqualTo(10)
        assertThat(transactionRepository.findAll().count { it.reason == CreditReason.CHAT_TURN }).isZero()
    }

    private fun regenerate(chatId: String, turnId: Long): String =
        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/regenerate/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"turnId":$turnId}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun seedStory(): Story {
        val story = storyRepository.save(
            Story(title = "호아킨 아카데미의 무속성 신입생", genre = "판타지"),
        )
        storySettingRepository.save(
            StorySetting(
                story = story,
                worldSetting = "마법 아카데미가 존재하는 세계",
                characterSetting = "강진우, 무속성 판정을 받은 1학년",
                userRoleSetting = "무속성 신입생",
                ruleSetting = "마법은 속성 발현으로만 사용한다.",
            ),
        )
        storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "강진우",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사가 시작된다.",
            ),
        )
        return story
    }
}
