package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatMessageRole
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryPlaySession
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
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
 * 이어쓰기 시 AI 서버로 보내는 history가 "최근 N턴"이 아니라 세션의 전체 대화 내역을
 * 시간순으로 담는지 검증한다. AI 클라이언트를 전달받은 요청을 캡처하는 가짜로 교체한다.
 *
 * 가짜 AI 빈 때문에 별도 ApplicationContext가 만들어지므로, 기본 테스트 컨텍스트와
 * 같은 이름의 in-memory H2를 공유하지 않도록 전용 DB 이름으로 분리한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-history-capture;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class ChatStreamHistoryIntegrationTests {

    /** 전달받은 [ChatTurnAiRequest]를 캡처하고 정상 결과를 반환하는 가짜 AI 클라이언트. */
    class CapturingChatTurnAiClient : ChatTurnAiClient {
        val lastRequest = AtomicReference<ChatTurnAiRequest>()

        override fun streamTurn(
            request: ChatTurnAiRequest,
            onToken: (String) -> Unit,
        ): ChatTurnAiResult {
            lastRequest.set(request)
            onToken("응답")
            return ChatTurnAiResult(aiOutput = "응답 본문", choices = listOf("선택 1", "선택 2"))
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
    private lateinit var storyPlaySessionRepository: StoryPlaySessionRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var storyChoiceRepository: StoryChoiceRepository

    @BeforeEach
    fun setUp() {
        storyChoiceRepository.deleteAllInBatch()
        storyMessageRepository.deleteAllInBatch()
        storyPlaySessionRepository.deleteAllInBatch()
        storyRepository.deleteAllInBatch()
    }

    @Test
    fun `이어쓰기는 최근 N턴이 아니라 세션의 전체 대화 내역을 시간순으로 AI에 전달한다`() {
        val story = storyRepository.save(Story(title = "전체 내역 전송 스토리", genre = "판타지"))
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        // SYSTEM 1건(order 1) + 11턴(USER/ASSISTANT, order 2..23) = 23건.
        // 최근 10턴 제한(메시지 20건)으로는 전체가 담기지 않는다.
        storyMessageRepository.save(
            StoryMessage(playSessionId = session.id, role = MessageRole.SYSTEM, content = "시스템 지시문", messageOrder = 1),
        )
        repeat(11) { i ->
            val turn = i + 1
            storyMessageRepository.save(
                StoryMessage(playSessionId = session.id, role = MessageRole.USER, content = "유저 메시지 $turn", messageOrder = turn * 2),
            )
            storyMessageRepository.save(
                StoryMessage(playSessionId = session.id, role = MessageRole.ASSISTANT, content = "AI 응답 $turn", messageOrder = turn * 2 + 1),
            )
        }

        stream(session.publicId.toString(), "다음 행동을 한다.")

        val captured = capturingAiClient.lastRequest.get()
            ?: error("AI 요청이 캡처되지 않았습니다.")

        // 전체 USER/ASSISTANT 22건이 그대로 전달된다(최근 10턴=20건 제한이 아니다).
        assertThat(captured.history).hasSize(22)
        // 시간순: 가장 오래된 USER가 처음, 직전 ASSISTANT가 마지막
        assertThat(captured.history.first().role).isEqualTo(ChatMessageRole.USER)
        assertThat(captured.history.first().content).isEqualTo("유저 메시지 1")
        assertThat(captured.history.last().role).isEqualTo(ChatMessageRole.ASSISTANT)
        assertThat(captured.history.last().content).isEqualTo("AI 응답 11")
        // SYSTEM 메시지는 history에서 제외한다.
        assertThat(captured.history).noneMatch { it.content == "시스템 지시문" }
        // 현재 입력은 아직 저장 전이므로 history에 포함되지 않는다.
        assertThat(captured.history).noneMatch { it.content == "다음 행동을 한다." }
        // 메모리(summary)는 아직 미적용 → 빈 문자열.
        assertThat(captured.summary).isEmpty()
    }

    private fun stream(chatId: String, userInput: String): String =
        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/stream")
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
