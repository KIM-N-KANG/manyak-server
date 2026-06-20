package com.knk.manyak.chat.controller

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryPlaySession
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

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
        storySettingRepository.deleteAllInBatch()
        storyStartSettingRepository.deleteAllInBatch()
        storyRepository.deleteAllInBatch()
    }

    @Test
    fun `이어쓰기는 started·token·completed 순서로 스트리밍하고 턴을 원자적으로 저장한다`() {
        val story = seedStory()
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        val body = stream(session.id, "마법수정에 손을 올린다.")

        // 이벤트 순서: started → token → completed
        val startedAt = body.indexOf("started")
        val tokenAt = body.indexOf("token")
        val completedAt = body.indexOf("completed")
        assertThat(startedAt).isGreaterThanOrEqualTo(0)
        assertThat(tokenAt).isGreaterThan(startedAt)
        assertThat(completedAt).isGreaterThan(tokenAt)

        // 저장 검증: USER(order 1) + ASSISTANT(order 2)
        val messages = storyMessageRepository.findByPlaySessionIdOrderByMessageOrderAsc(session.id)
        assertThat(messages).hasSize(2)
        val user = messages[0]
        val assistant = messages[1]
        assertThat(user.role).isEqualTo(MessageRole.USER)
        assertThat(user.content).isEqualTo("마법수정에 손을 올린다.")
        assertThat(user.messageOrder).isEqualTo(1)
        assertThat(assistant.role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(assistant.messageOrder).isEqualTo(2)
        assertThat(assistant.content).isNotBlank()

        // 선택지 N행 저장
        val choices = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id)
        assertThat(choices).isNotEmpty
        assertThat(choices.map { it.choiceOrder.toInt() }).startsWith(1)

        // current_turn++
        val reloaded = storyPlaySessionRepository.findById(session.id).orElseThrow()
        assertThat(reloaded.currentTurn).isEqualTo(1)

        // completed 페이로드: turnId=ASSISTANT id, aiOutput, choices 포함
        assertThat(body).contains("\"turnId\":${assistant.id}")
        assertThat(body).contains("\"aiOutput\"")
        assertThat(body).contains("\"choices\"")
    }

    @Test
    fun `다음 턴은 직전 메시지 순서에 이어 저장하고 current_turn을 누적한다`() {
        val story = seedStory()
        val session = storyPlaySessionRepository.save(
            StoryPlaySession(storyId = story.id, currentTurn = 1),
        )
        // 이미 1턴(order 1,2)이 쌓여 있는 상태
        storyMessageRepository.save(StoryMessage(playSessionId = session.id, role = MessageRole.USER, content = "이름은 강진우야.", messageOrder = 1))
        storyMessageRepository.save(StoryMessage(playSessionId = session.id, role = MessageRole.ASSISTANT, content = "기록판에 새겨졌다.", messageOrder = 2))

        stream(session.id, "앞으로 나선다.")

        val messages = storyMessageRepository.findByPlaySessionIdOrderByMessageOrderAsc(session.id)
        assertThat(messages).hasSize(4)
        assertThat(messages[2].role).isEqualTo(MessageRole.USER)
        assertThat(messages[2].content).isEqualTo("앞으로 나선다.")
        assertThat(messages[2].messageOrder).isEqualTo(3)
        assertThat(messages[3].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(messages[3].messageOrder).isEqualTo(4)

        val reloaded = storyPlaySessionRepository.findById(session.id).orElseThrow()
        assertThat(reloaded.currentTurn).isEqualTo(2)
    }

    @Test
    fun `채팅 목록의 chatCount는 실제 이어쓰기를 거치며 누적된다`() {
        val story = seedStory()
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        // 막 생성한 채팅: 진행 이력 없음 → chatCount 0
        assertChatCount(session.id, 0)

        // 1턴 이어쓰기 → persistTurn이 current_turn을 1로 증가 → 목록에 1로 반영
        stream(session.id, "마법수정에 손을 올린다.")
        assertChatCount(session.id, 1)

        // 2턴 이어쓰기 → 2로 누적
        stream(session.id, "앞으로 나선다.")
        assertChatCount(session.id, 2)
    }

    @Test
    fun `존재하지 않는 채팅으로 이어쓰면 404로 응답하고 아무것도 저장하지 않는다`() {
        restTestClient.post()
            .uri("/api/v1/chats/999999/turns/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"손을 올린다."}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("채팅을 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/chats/999999/turns/stream")

        assertThat(storyMessageRepository.count()).isZero()
    }

    @Test
    fun `빈 입력으로 이어쓰면 400으로 응답한다`() {
        val story = seedStory()
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        restTestClient.post()
            .uri("/api/v1/chats/${session.id}/turns/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"   "}""")
            .exchange()
            .expectStatus().isBadRequest

        assertThat(storyMessageRepository.count()).isZero()
    }

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

    @Test
    fun `스트리밍 응답 Content-Type은 charset=UTF-8을 포함한다`() {
        val story = seedStory()
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        val contentType = restTestClient.post()
            .uri("/api/v1/chats/${session.id}/turns/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"손을 올린다."}""")
            .exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseHeaders
            .contentType

        assertThat(contentType).isNotNull()
        assertThat(contentType!!.charset).isEqualTo(Charsets.UTF_8)
    }

    private fun assertChatCount(chatId: Long, expected: Int) {
        restTestClient.post()
            .uri("/api/v1/chats/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"chatIds":[$chatId]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].id").isEqualTo(chatId)
            .jsonPath("$[0].chatCount").isEqualTo(expected)
    }

    private fun stream(chatId: Long, userInput: String): String =
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
