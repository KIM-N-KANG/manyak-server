package com.knk.manyak.chat.controller

import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.entity.PlaySessionStatus
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
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
class ChatCreateControllerIntegrationTests {

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

    @BeforeEach
    fun setUp() {
        storyMessageRepository.deleteAllInBatch()
        storyPlaySessionRepository.deleteAllInBatch()
        storyStartSettingRepository.deleteAllInBatch()
        storyRepository.deleteAllInBatch()
    }

    @Test
    fun `채팅을 생성하면 201과 함께 ACTIVE 플레이 세션이 만들어진다`() {
        val story = storyRepository.save(
            Story(
                title = "호아킨 아카데미의 무속성 신입생",
                oneLineIntro = "속성을 잃은 신입생의 첫날.",
                description = "마법 아카데미 입학 적성 검사 이야기.",
                genre = "판타지",
            ),
        )
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "입학 적성 검사",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사 직전의 검사장.",
            ),
        )

        val response = restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":${story.id}}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(CreateChatResponse::class.java)
            .returnResult()
            .responseBody!!

        assertThat(response.storyId).isEqualTo(story.id)
        assertThat(response.prologue).isEqualTo("마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.")
        assertThat(response.createdAt).isNotNull()

        val sessions = storyPlaySessionRepository.findAll()
        assertThat(sessions).hasSize(1)
        val session = sessions.first()
        // 응답 id는 순차 PK가 아니라 추측 불가능한 공개 식별자(publicId)다 (IDOR 방지)
        assertThat(response.id).isEqualTo(session.publicId.toString())
        assertThat(response.id).isNotEqualTo(session.id.toString())
        assertThat(session.storyId).isEqualTo(story.id)
        assertThat(session.startSettingId).isEqualTo(startSetting.id)
        assertThat(session.status).isEqualTo(PlaySessionStatus.ACTIVE)
        assertThat(session.userId).isNull()

        // 오프닝 메시지는 저장하지 않는다 (prologue는 start_setting이 단일 출처)
        assertThat(storyMessageRepository.count()).isZero()
    }

    @Test
    fun `시작 설정이 없는 스토리도 빈 프롤로그로 채팅이 생성된다`() {
        val story = storyRepository.save(
            Story(title = "설정 미완 스토리"),
        )

        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":${story.id}}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.storyId").isEqualTo(story.id)
            .jsonPath("$.prologue").isEqualTo("")

        val session = storyPlaySessionRepository.findAll().first()
        assertThat(session.startSettingId).isNull()
    }

    @Test
    fun `존재하지 않는 스토리로 채팅을 생성하면 404로 응답한다`() {
        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":999999}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/chats")

        assertThat(storyPlaySessionRepository.count()).isZero()
    }

    @Test
    fun `storyId가 올바르지 않으면 400으로 응답한다`() {
        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":0}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)

        assertThat(storyPlaySessionRepository.count()).isZero()
    }
}
