package com.knk.manyak.chat.controller

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.service.ChatGenerationStore
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.ScriptedAiIntegrationTest
import com.knk.manyak.support.awaitGenerationReleased
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.jpa.repository.Query
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/**
 * 턴 한 번이 대화 이력 전체(본문 포함, LIMIT 없음)를 몇 번 읽는지 고정한다(KNK-2074).
 *
 * 남는 로드는 트랜잭션 경계마다 하나씩이다. 잠금 전 동기 검증(재생성·입력 수정), 조립 트랜잭션(한 스냅샷을 공유),
 * 저장 트랜잭션의 입력 수정 숨김. 마지막 턴 id만 필요한 시작 상태 확인과 저장 시 재확인은 단건 조회를 쓴다.
 */
class ChatTurnHistoryLoadIntegrationTests : ScriptedAiIntegrationTest() {
    @Autowired private lateinit var http: RestTestClient
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    @Autowired private lateinit var generationStore: ChatGenerationStore
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    private val historyQuery = StoryMessageRepository::class.java
        .getMethod("findByChatIdOrderByMessageOrderAsc", Long::class.java)
        .getAnnotation(Query::class.java).value

    private fun historyLoads() =
        entityManagerFactory.unwrap(SessionFactory::class.java).statistics.getQueryStatistics(historyQuery).executionCount

    @BeforeEach fun setUp() = cleaner.cleanAll()

    private fun seedChat(turns: Int = 3): Pair<StoryChat, Long> {
        val story = stories.save(Story(title = "이력 로드 횟수", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id, currentTurn = turns))
        var lastAssistant = 0L
        (1..turns).forEach { turn ->
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력 $turn", messageOrder = turn * 2 - 1))
            lastAssistant = messages.save(
                StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "응답 $turn", messageOrder = turn * 2),
            ).id
        }
        return chat to lastAssistant
    }

    private fun countLoads(block: () -> Unit): Long {
        entityManagerFactory.unwrap(SessionFactory::class.java).statistics.clear()
        block()
        return historyLoads()
    }

    private fun stream(chat: StoryChat, path: String, body: String) {
        val response = http.post().uri("/api/v1/chats/${chat.publicId}/turns/$path")
            .header("X-Manyak-Device-Id", "history-load-${UUID.randomUUID()}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body(body)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody
        assertThat(response).contains("completed")
        generationStore.awaitGenerationReleased(chat.publicId.toString())
    }

    @Test
    fun `이어쓰기는 이력 전체를 조립에서 한 번만 읽는다`() {
        val (chat, _) = seedChat()

        assertThat(countLoads { stream(chat, "stream", """{"userInput":"다음 행동"}""") }).isEqualTo(1)
    }

    @Test
    fun `재생성은 잠금 전 검증과 조립에서 한 번씩 읽는다`() {
        val (chat, last) = seedChat()

        assertThat(countLoads { stream(chat, "regenerate/stream", """{"turnId":$last}""") }).isEqualTo(2)
    }

    @Test
    fun `입력 수정은 잠금 전 검증과 조립과 저장 숨김에서 한 번씩 읽는다`() {
        val (chat, last) = seedChat()
        val body = """{"turnId":$last,"userInput":"고친 입력","userSource":"typed","realtimeImage":false}"""

        assertThat(countLoads { stream(chat, "edit/stream", body) }).isEqualTo(3)
    }

    @Test
    fun `선택지 생성은 조립에서 한 번만 읽는다`() {
        val (chat, last) = seedChat()

        val loads = countLoads {
            http.post().uri("/api/v1/chats/${chat.publicId}/turns/$last/choices").exchange().expectStatus().isOk
        }

        assertThat(loads).isEqualTo(1)
    }
}
