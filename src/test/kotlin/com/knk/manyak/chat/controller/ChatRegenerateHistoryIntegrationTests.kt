package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatMessageRole
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
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
import java.util.concurrent.atomic.AtomicReference

/**
 * 재생성(§4-3-9) 시 AI로 보내는 history가 "마지막 턴(USER·ASSISTANT 쌍)을 제외한 1..N-1"인지,
 * userInput이 마지막 사용자 입력을 그대로 재전송하는지 검증한다. AI 클라이언트를 요청 캡처 가짜로 교체한다.
 *
 * 가짜 AI 빈 때문에 별도 ApplicationContext가 만들어지므로 전용 in-memory H2로 분리한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-regen-history;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class ChatRegenerateHistoryIntegrationTests {

    class CapturingChatTurnAiClient : ChatTurnAiClient {
        val lastRequest = AtomicReference<ChatTurnAiRequest>()

        override fun streamTurn(
            request: ChatTurnAiRequest,
            onToken: (String) -> Unit,
        ): ChatTurnAiResult {
            lastRequest.set(request)
            onToken("응답")
            return ChatTurnAiResult(aiOutput = "재생성 본문", choices = listOf("선택 1", "선택 2"))
        }
    }

    @TestConfiguration
    class CapturingAiClientConfig {
        @Bean
        @Primary
        fun capturingChatTurnAiClient(): CapturingChatTurnAiClient = CapturingChatTurnAiClient()
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var capturingAiClient: CapturingChatTurnAiClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `재생성은 마지막 턴을 제외한 이전 내역을 history로 보내고 마지막 입력을 그대로 재전송한다`() {
        val story = storyRepository.save(Story(title = "재생성 내역 스토리", genre = "판타지"))
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 3))

        // SYSTEM 1건(order 1) + 3턴(order 2..7).
        storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.SYSTEM, content = "시스템 지시문", messageOrder = 1),
        )
        repeat(3) { i ->
            val turn = i + 1
            storyMessageRepository.save(
                StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "유저 메시지 $turn", messageOrder = turn * 2),
            )
            storyMessageRepository.save(
                StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "AI 응답 $turn", messageOrder = turn * 2 + 1),
            )
        }
        val lastAssistant = storyMessageRepository.findFirstByChatIdOrderByMessageOrderDesc(chat.id)!!

        regenerate(chat.publicId.toString(), lastAssistant.id)

        val captured = capturingAiClient.lastRequest.get()
            ?: error("AI 요청이 캡처되지 않았습니다.")

        // history: 마지막 턴(유저 메시지 3 + AI 응답 3)을 제외한 앞 2턴 = 4건.
        assertThat(captured.history).hasSize(4)
        assertThat(captured.history.first().role).isEqualTo(ChatMessageRole.USER)
        assertThat(captured.history.first().content).isEqualTo("유저 메시지 1")
        assertThat(captured.history.last().role).isEqualTo(ChatMessageRole.ASSISTANT)
        assertThat(captured.history.last().content).isEqualTo("AI 응답 2")
        // 마지막 턴의 USER·ASSISTANT는 history에서 제외된다.
        assertThat(captured.history).noneMatch { it.content == "유저 메시지 3" }
        assertThat(captured.history).noneMatch { it.content == "AI 응답 3" }
        // SYSTEM은 제외한다.
        assertThat(captured.history).noneMatch { it.content == "시스템 지시문" }
        // userInput은 마지막 사용자 입력을 그대로 재전송한다.
        assertThat(captured.userInput).isEqualTo("유저 메시지 3")
        assertThat(captured.summary).isEmpty()
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
}
