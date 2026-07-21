package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatChoicesResult
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.service.ChatTurnPersister
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallLogRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * 채팅 선택지 생성 전용 엔드포인트(KNK-636, 스펙 §4-3-3) 통합 검증.
 *
 * 마지막 턴에 선택지 3개를 생성·저장하고, 멱등(이미 있으면 AI 미호출)·비마지막턴 409·턴0개 404·AI실패 502·
 * ai_call_logs choice_generation 적재를 확인한다. AI는 토글 가능한 @Primary 페이크로 대체한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatChoicesControllerIntegrationTests {

    companion object {
        val generateChoicesCalls = AtomicInteger(0)

        @Volatile
        var failChoices = false

        val stubChoices = listOf("주변을 살핀다.", "한 걸음 나선다.", "자리를 벗어난다.")
    }

    @TestConfiguration
    class FakeAiClientConfig {
        @Bean
        @Primary
        fun fakeChatTurnAiClient(): ChatTurnAiClient = object : ChatTurnAiClient {
            override fun streamTurn(request: ChatTurnAiRequest, onToken: (String) -> Unit): ChatTurnAiResult =
                error("streamTurn은 이 테스트에서 사용하지 않는다")

            override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String): ChatChoicesResult {
                generateChoicesCalls.incrementAndGet()
                if (failChoices) {
                    throw IllegalStateException("AI 선택지 강제 실패")
                }
                return ChatChoicesResult(choices = stubChoices)
            }
        }
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storySettingRepository: StorySettingRepository
    @Autowired private lateinit var storyStartSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var storyMessageRepository: StoryMessageRepository
    @Autowired private lateinit var storyChoiceRepository: StoryChoiceRepository
    @Autowired private lateinit var aiCallLogRepository: AiCallLogRepository
    @Autowired private lateinit var chatTurnPersister: ChatTurnPersister
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        generateChoicesCalls.set(0)
        failChoices = false
        databaseCleaner.cleanAll()
    }

    @Test
    fun `마지막 턴의 선택지를 생성해 저장하고 choice_generation 로그를 남긴다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val assistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "본문", messageOrder = 2),
        )

        postChoices(chat.publicId.toString(), assistant.id)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.choices.length()").isEqualTo(3)

        val saved = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id)
        assertThat(saved.map { it.choiceText }).isEqualTo(stubChoices)
        assertThat(saved.map { it.choiceOrder.toInt() }).containsExactly(1, 2, 3)

        // ai_call_logs에 choice_generation 행이 turn_number와 함께 적재된다(chat_response와 chat_id+turn_number로 조인).
        val choiceLogs = aiCallLogRepository.findAll().filter { it.feature == AiCallFeature.CHOICE_GENERATION }
        assertThat(choiceLogs).hasSize(1)
        assertThat(choiceLogs.first().turnNumber).isEqualTo(1)
        assertThat(choiceLogs.first().chatId).isEqualTo(chat.publicId)
    }

    @Test
    fun `이미 선택지가 있으면 AI 없이 기존 값을 반환한다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val assistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "본문", messageOrder = 2),
        )
        storyChoiceRepository.save(StoryChoice(chatId = chat.id, messageId = assistant.id, choiceText = "기존 A", choiceOrder = 1))
        storyChoiceRepository.save(StoryChoice(chatId = chat.id, messageId = assistant.id, choiceText = "기존 B", choiceOrder = 2))

        postChoices(chat.publicId.toString(), assistant.id)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.choices[0]").isEqualTo("기존 A")
            .jsonPath("$.choices.length()").isEqualTo(2)

        // 멱등: AI를 호출하지 않고 choice_generation 로그도 남기지 않는다.
        assertThat(generateChoicesCalls.get()).isZero()
        assertThat(aiCallLogRepository.findAll().none { it.feature == AiCallFeature.CHOICE_GENERATION }).isTrue()
    }

    @Test
    fun `마지막 턴이 아닌 turnId는 409로 거절한다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 2))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "첫 입력", messageOrder = 1))
        val firstAssistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "첫 응답", messageOrder = 2),
        )
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "둘째 입력", messageOrder = 3))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "둘째 응답", messageOrder = 4))

        // 마지막이 아닌(첫 턴) ASSISTANT id로 요청 → 409.
        postChoices(chat.publicId.toString(), firstAssistant.id)
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `비마지막 턴에 선택지가 있어도 409로 거절한다`() {
        // Codex P1: 멱등 사전 검사가 검증보다 먼저면 비마지막 턴의 저장된 선택지를 200으로 돌려준다. 검증(409)이 우선해야 한다.
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 2))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "첫 입력", messageOrder = 1))
        val firstAssistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "첫 응답", messageOrder = 2),
        )
        // 비마지막(첫) 턴에 선택지가 이미 있는 상태.
        storyChoiceRepository.save(StoryChoice(chatId = chat.id, messageId = firstAssistant.id, choiceText = "첫턴 선택", choiceOrder = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "둘째 입력", messageOrder = 3))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "둘째 응답", messageOrder = 4))

        postChoices(chat.publicId.toString(), firstAssistant.id)
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `타 채팅의 turnId로는 선택지를 받을 수 없다`() {
        // Codex P1 IDOR: 다른 채팅의 ASSISTANT 메시지 id(선택지 보유)를 이 채팅에 넣어도 검증에서 409로 막혀 남의 선택지가 새지 않는다.
        val story = seedStory()
        val chatA = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chatA.id, role = MessageRole.USER, content = "A 입력", messageOrder = 1))
        storyMessageRepository.save(StoryMessage(chatId = chatA.id, role = MessageRole.ASSISTANT, content = "A 응답", messageOrder = 2))

        val chatB = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chatB.id, role = MessageRole.USER, content = "B 입력", messageOrder = 1))
        val bAssistant = storyMessageRepository.save(
            StoryMessage(chatId = chatB.id, role = MessageRole.ASSISTANT, content = "B 응답", messageOrder = 2),
        )
        storyChoiceRepository.save(StoryChoice(chatId = chatB.id, messageId = bAssistant.id, choiceText = "B 비밀 선택", choiceOrder = 1))

        // chatA 경로에 chatB의 turnId → 409(남의 선택지 노출 없음).
        postChoices(chatA.publicId.toString(), bAssistant.id)
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `턴이 0개인 채팅은 404로 응답한다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))

        postChoices(chat.publicId.toString(), 1)
            .expectStatus().isNotFound
    }

    @Test
    fun `순차 정수 chatId는 404로 응답한다`() {
        // IDOR 방지: 순차 정수는 공개 식별자가 아니다.
        postChoices("999999", 1)
            .expectStatus().isNotFound
    }

    @Test
    fun `AI 선택지 생성 실패는 502로 응답한다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val assistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "본문", messageOrder = 2),
        )
        failChoices = true

        postChoices(chat.publicId.toString(), assistant.id)
            .expectStatus().isEqualTo(502)

        // 실패 시 선택지는 저장되지 않는다.
        assertThat(storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id)).isEmpty()
    }

    @Test
    fun `본문이 재생성돼 바뀌면 낡은 선택지를 저장하지 않고 409를 던진다`() {
        // Codex P1: AI 호출 중 같은 턴이 제자리 재생성되면(id 유지·content 교체) 낡은 본문 기준 선택지를 저장하면 안 된다.
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val assistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "재생성된 새 본문", messageOrder = 2),
        )

        // 선택지는 낡은 본문 기준으로 생성됐다고 가정 → 현재 본문과 불일치 → 409, 저장되지 않는다.
        assertThatThrownBy {
            chatTurnPersister.fillChoices(chat.id, assistant.id, "낡은 본문", listOf("A", "B", "C"))
        }.matches { (it as org.springframework.web.server.ResponseStatusException).statusCode.value() == 409 }
        assertThat(storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id)).isEmpty()
    }

    @Test
    fun `경합으로 이미 저장된 선택지가 있으면 늦은 결과 대신 저장된 값을 반환한다`() {
        // Codex P2: 동시 호출이 먼저 저장했으면 늦은 호출의 AI 결과를 버리고 실재하는 저장 값을 돌려줘야 한다.
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val assistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "본문", messageOrder = 2),
        )
        storyChoiceRepository.save(StoryChoice(chatId = chat.id, messageId = assistant.id, choiceText = "먼저 저장 A", choiceOrder = 1))

        val filled = chatTurnPersister.fillChoices(chat.id, assistant.id, "본문", listOf("늦은 X", "늦은 Y", "늦은 Z"))

        assertThat(filled.choices).containsExactly("먼저 저장 A")
        // 저장은 늦은 값으로 덮이지 않는다.
        assertThat(storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id).map { it.choiceText })
            .containsExactly("먼저 저장 A")
    }

    private fun postChoices(chatId: String, turnId: Long): RestTestClient.ResponseSpec =
        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/$turnId/choices")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()

    private fun seedStory(): Story {
        val story = storyRepository.save(Story(title = "호아킨 아카데미", genre = "판타지"))
        storySettingRepository.save(
            StorySetting(
                story = story,
                worldSetting = "마법 아카데미가 존재하는 세계",
                characterSetting = "강진우, 무속성 1학년",
                userRoleSetting = "무속성 신입생",
                ruleSetting = "마법은 속성 발현으로만 사용한다.",
            ),
        )
        storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "강진우",
                prologue = "당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사가 시작된다.",
            ),
        )
        return story
    }
}
