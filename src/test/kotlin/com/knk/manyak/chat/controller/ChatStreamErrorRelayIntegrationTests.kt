package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiException
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.entity.StoryPlaySession
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
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

/**
 * AI가 구조화된 오류({code,message})를 던지면 그대로 SSE error 이벤트로 relay하고
 * 어떤 메시지도 저장하지 않는지 검증한다. AI 클라이언트를 예외를 던지는 가짜로 교체한다.
 *
 * 가짜 AI 빈 때문에 별도 ApplicationContext가 만들어지므로, 기본 테스트 컨텍스트와
 * 같은 이름의 in-memory H2를 공유해 create-drop이 서로 간섭하지 않도록 DB를 분리한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-error-relay;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class ChatStreamErrorRelayIntegrationTests {

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
    private lateinit var storyPlaySessionRepository: StoryPlaySessionRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var storyChoiceRepository: StoryChoiceRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `AI 오류는 code와 message를 error 이벤트로 relay하고 아무것도 저장하지 않는다`() {
        val story = storyRepository.save(Story(title = "설정 미완 스토리", genre = "판타지"))
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        val body = restTestClient.post()
            .uri("/api/v1/chats/${session.publicId}/turns/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"손을 올린다."}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")

        assertThat(body).contains("error")
        assertThat(body).contains("AI_TIMEOUT")
        assertThat(body).contains("AI 응답이 시간 내에 도착하지 않았습니다.")

        // 턴 원자성: 실패 시 USER·ASSISTANT·choices 아무것도 저장되지 않는다
        assertThat(storyMessageRepository.count()).isZero()
        assertThat(storyChoiceRepository.count()).isZero()
        val reloaded = storyPlaySessionRepository.findById(session.id).orElseThrow()
        assertThat(reloaded.currentTurn).isZero()
    }
}
