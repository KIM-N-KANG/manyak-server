package com.knk.manyak.chat.controller

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryPlaySession
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
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
class ChatQueryControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

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
        storyStartSettingRepository.deleteAllInBatch()
        storyRepository.deleteAllInBatch()
    }

    @Test
    fun `채팅 상세는 USER+ASSISTANT를 턴으로 페어링하고 턴별 선택지를 포함한다`() {
        val story = storyRepository.save(Story(title = "호아킨 아카데미의 무속성 신입생"))
        storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "입학 적성 검사",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사 직전의 검사장.",
            ),
        )
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        message(session.id, MessageRole.USER, "이름은 강진우야.", 1)
        val assistant1 = message(session.id, MessageRole.ASSISTANT, "강진우라는 이름이 기록판에 새겨졌다.", 2)
        message(session.id, MessageRole.USER, "마법수정에 손을 올린다.", 3)
        val assistant2 = message(session.id, MessageRole.ASSISTANT, "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.", 4)

        choice(session.id, assistant1.id, "주변을 살핀다.", 1)
        choice(session.id, assistant1.id, "앞으로 나선다.", 2)
        choice(session.id, assistant2.id, "자리를 벗어난다.", 1)

        restTestClient.get()
            .uri("/api/v1/chats/${session.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(session.publicId.toString())
            .jsonPath("$.storyId").isEqualTo(story.id)
            .jsonPath("$.storyTitle").isEqualTo("호아킨 아카데미의 무속성 신입생")
            .jsonPath("$.prologue").isEqualTo("마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.")
            .jsonPath("$.turns.length()").isEqualTo(2)
            .jsonPath("$.turns[0].id").isEqualTo(assistant1.id)
            .jsonPath("$.turns[0].userInput").isEqualTo("이름은 강진우야.")
            .jsonPath("$.turns[0].aiOutput").isEqualTo("강진우라는 이름이 기록판에 새겨졌다.")
            .jsonPath("$.turns[0].choices.length()").isEqualTo(2)
            .jsonPath("$.turns[0].choices[0]").isEqualTo("주변을 살핀다.")
            .jsonPath("$.turns[0].choices[1]").isEqualTo("앞으로 나선다.")
            .jsonPath("$.turns[1].id").isEqualTo(assistant2.id)
            .jsonPath("$.turns[1].userInput").isEqualTo("마법수정에 손을 올린다.")
            .jsonPath("$.turns[1].aiOutput").isEqualTo("검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
            .jsonPath("$.turns[1].choices.length()").isEqualTo(1)
            .jsonPath("$.turns[1].choices[0]").isEqualTo("자리를 벗어난다.")
    }

    @Test
    fun `시작 설정이 없는 채팅 상세는 빈 프롤로그로 조회된다`() {
        val story = storyRepository.save(Story(title = "설정 미완 스토리"))
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        restTestClient.get()
            .uri("/api/v1/chats/${session.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.storyTitle").isEqualTo("설정 미완 스토리")
            .jsonPath("$.prologue").isEqualTo("")
            .jsonPath("$.turns.length()").isEqualTo(0)
    }

    @Test
    fun `순차 정수 ID로 채팅 상세를 조회하면 404로 응답한다`() {
        // IDOR 방지: 외부 식별자는 공개 UUID이므로 순차 정수를 추측해도 열람할 수 없다.
        restTestClient.get()
            .uri("/api/v1/chats/999999")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("채팅을 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/chats/999999")
    }

    @Test
    fun `존재하지 않는 임의 UUID로 채팅 상세를 조회하면 404로 응답한다`() {
        // 형식이 올바른 UUID라도 존재하지 않으면 동일하게 404. 존재 여부를 노출하지 않는다.
        val missing = java.util.UUID.randomUUID()
        restTestClient.get()
            .uri("/api/v1/chats/$missing")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("채팅을 찾을 수 없습니다.")
    }

    @Test
    fun `채팅 목록은 요청한 ID 순서를 보존하고 마지막 AI 출력으로 프리뷰를 만든다`() {
        val storyA = storyRepository.save(Story(title = "호아킨 아카데미의 무속성 신입생"))
        val storyB = storyRepository.save(Story(title = "왕국의 마지막 편지"))
        // chatCount는 세션의 비정규화 카운터(currentTurn)를 그대로 노출한다. persistTurn을 우회해
        // 직접 시드하므로 currentTurn을 명시한다. (A: 2턴, B: 1턴)
        val sessionA = storyPlaySessionRepository.save(StoryPlaySession(storyId = storyA.id, currentTurn = 2))
        val sessionB = storyPlaySessionRepository.save(StoryPlaySession(storyId = storyB.id, currentTurn = 1))

        message(sessionA.id, MessageRole.USER, "이름은 강진우야.", 1)
        message(sessionA.id, MessageRole.ASSISTANT, "강진우라는 이름이 기록판에 새겨졌다.", 2)
        message(sessionA.id, MessageRole.USER, "마법수정에 손을 올린다.", 3)
        message(sessionA.id, MessageRole.ASSISTANT, "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.", 4)
        message(sessionB.id, MessageRole.USER, "편지를 연다.", 1)
        message(sessionB.id, MessageRole.ASSISTANT, "봉인이 풀린 편지 끝에서 오래된 왕가의 문장이 희미하게 떠올랐다.", 2)

        // 요청 순서: B, 없는 ID, A → 응답은 B, A 순서로 보존되고 없는 ID는 제외된다
        val missing = java.util.UUID.randomUUID()
        restTestClient.post()
            .uri("/api/v1/chats/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"chatIds":["${sessionB.publicId}","$missing","${sessionA.publicId}"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].id").isEqualTo(sessionB.publicId.toString())
            .jsonPath("$[0].storyId").isEqualTo(storyB.id)
            .jsonPath("$[0].storyTitle").isEqualTo("왕국의 마지막 편지")
            .jsonPath("$[0].lastStoryPreview").isEqualTo("봉인이 풀린 편지 끝에서 오래된 왕가의 문장이 희미하게 떠올랐다.")
            .jsonPath("$[0].chatCount").isEqualTo(1)
            .jsonPath("$[1].id").isEqualTo(sessionA.publicId.toString())
            .jsonPath("$[1].storyId").isEqualTo(storyA.id)
            .jsonPath("$[1].storyTitle").isEqualTo("호아킨 아카데미의 무속성 신입생")
            .jsonPath("$[1].lastStoryPreview").isEqualTo("검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
            .jsonPath("$[1].chatCount").isEqualTo(2)
    }

    @Test
    fun `진행 이력이 없는 채팅 목록 항목의 chatCount는 0이다`() {
        val story = storyRepository.save(Story(title = "아직 시작 안 한 스토리"))
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        restTestClient.post()
            .uri("/api/v1/chats/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"chatIds":["${session.publicId}"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].id").isEqualTo(session.publicId.toString())
            .jsonPath("$[0].chatCount").isEqualTo(0)
    }

    private fun message(playSessionId: Long, role: MessageRole, content: String, order: Int): StoryMessage =
        storyMessageRepository.save(
            StoryMessage(
                playSessionId = playSessionId,
                role = role,
                content = content,
                messageOrder = order,
            ),
        )

    private fun choice(playSessionId: Long, messageId: Long, text: String, order: Int): StoryChoice =
        storyChoiceRepository.save(
            StoryChoice(
                playSessionId = playSessionId,
                messageId = messageId,
                choiceText = text,
                choiceOrder = order.toShort(),
            ),
        )
}
