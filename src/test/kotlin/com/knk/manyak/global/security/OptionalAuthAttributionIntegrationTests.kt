package com.knk.manyak.global.security

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
import com.knk.manyak.feedback.repository.FeedbackRepository
import com.knk.manyak.story.client.AiResponseMeta
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStoryMeta
import com.knk.manyak.story.client.AiStorySettings
import com.knk.manyak.story.client.AiStoryStartSettings
import com.knk.manyak.story.client.AiStoryItem
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCreationExample
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationExampleRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * KNK-286: 익명 허용(permitAll) 도메인 경로의 optional 인증/귀속 통합 검증.
 *
 * 각 쓰기 엔드포인트에 대해 세 시나리오를 확인한다.
 * - 유효 Bearer access → 201/200 + 저장 엔티티 user_id == 그 사용자 내부 id
 * - 만료/위조 Bearer → 201/200(절대 401 아님) + user_id == null
 * - 토큰 없음 → 201/200 + user_id == null
 *
 * 대상: feedback, chat 생성, simple story 생성(AI 클라이언트는 @Primary 페이크로 대체).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OptionalAuthAttributionIntegrationTests {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun disableSlackWebhook(registry: DynamicPropertyRegistry) {
            registry.add("manyak.slack.feedback-webhook-url") { "" }
        }
    }

    @TestConfiguration
    class FakeAiClientConfig {
        // 테스트는 AI 서버 없이 도메인 귀속만 검증한다. 실제 HTTP 호출 대신 고정 응답을 반환한다.
        @Bean
        @Primary
        fun fakeStoryAiClient(): StoryAiClient = object : StoryAiClient {
            override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse =
                AiStorylinesResponse(stories = emptyList(), meta = AiResponseMeta())

            override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse =
                AiStoryCompileResponse(
                    stories = AiStoryMeta(
                        title = "생성된 스토리",
                        oneLineIntro = "한 줄 소개",
                        description = "설명",
                    ),
                    storySettings = AiStorySettings(
                        worldSetting = "세계관",
                        characterSetting = "캐릭터",
                        userRoleSetting = "역할",
                        ruleSetting = "규칙",
                    ),
                    storyStartSettings = AiStoryStartSettings(
                        name = "시작",
                        startSituation = "상황",
                        prologue = "프롤로그",
                    ),
                    storySuggestedInputs = listOf("추천1"),
                    meta = AiResponseMeta(),
                )
        }
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var properties: AuthProperties

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storyPlaySessionRepository: StoryPlaySessionRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var creationSessionRepository: StoryCreationSessionRepository

    @Autowired
    private lateinit var creationExampleRepository: StoryCreationExampleRepository

    @BeforeEach
    fun setUp() {
        feedbackRepository.deleteAllInBatch()
        storyMessageRepository.deleteAllInBatch()
        storyPlaySessionRepository.deleteAllInBatch()
        creationExampleRepository.deleteAllInBatch()
        creationSessionRepository.deleteAllInBatch()
        storySuggestedInputRepository.deleteAllInBatch()
        storySettingRepository.deleteAllInBatch()
        storyStartSettingRepository.deleteAllInBatch()
        storyRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    private fun saveUser(): User =
        userRepository.save(User(nickname = "manyak_user", status = UserStatus.ACTIVE))

    private fun validToken(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    private fun expiredToken(user: User): String {
        val past = Instant.now().minus(Duration.ofHours(2))
        return JwtTokenProvider(properties, Clock.fixed(past, ZoneOffset.UTC)).issueAccessToken(user.publicId)
    }

    private val forgedToken: String = "not-a-real-jwt.forged.token"

    // ---- POST /api/v1/feedbacks ----

    @Test
    fun `유효 토큰으로 피드백을 등록하면 201과 함께 user_id가 귀속된다`() {
        val user = saveUser()

        postFeedback("Bearer ${validToken(user)}").expectStatus().isCreated

        assertThat(feedbackRepository.findAll().first().userId).isEqualTo(user.id)
    }

    @Test
    fun `만료 토큰으로 피드백을 등록하면 401이 아니라 201이고 user_id는 null이다`() {
        val user = saveUser()

        postFeedback("Bearer ${expiredToken(user)}").expectStatus().isCreated

        assertThat(feedbackRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `위조 토큰으로 피드백을 등록하면 401이 아니라 201이고 user_id는 null이다`() {
        postFeedback("Bearer $forgedToken").expectStatus().isCreated

        assertThat(feedbackRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `토큰 없이 피드백을 등록하면 201이고 user_id는 null이다`() {
        postFeedback(null).expectStatus().isCreated

        assertThat(feedbackRepository.findAll().first().userId).isNull()
    }

    private fun postFeedback(authorization: String?): RestTestClient.ResponseSpec {
        val spec = restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header("Authorization", it) }
        return spec.body("""{"body":"피드백 본문입니다."}""").exchange()
    }

    // ---- POST /api/v1/chats ----

    @Test
    fun `유효 토큰으로 채팅을 생성하면 201과 함께 user_id가 귀속된다`() {
        val user = saveUser()
        val story = storyRepository.save(Story(title = "스토리"))

        postChat(story.publicId, "Bearer ${validToken(user)}").expectStatus().isCreated

        assertThat(storyPlaySessionRepository.findAll().first().userId).isEqualTo(user.id)
    }

    @Test
    fun `만료 토큰으로 채팅을 생성하면 401이 아니라 201이고 user_id는 null이다`() {
        val user = saveUser()
        val story = storyRepository.save(Story(title = "스토리"))

        postChat(story.publicId, "Bearer ${expiredToken(user)}").expectStatus().isCreated

        assertThat(storyPlaySessionRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `위조 토큰으로 채팅을 생성하면 401이 아니라 201이고 user_id는 null이다`() {
        val story = storyRepository.save(Story(title = "스토리"))

        postChat(story.publicId, "Bearer $forgedToken").expectStatus().isCreated

        assertThat(storyPlaySessionRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `토큰 없이 채팅을 생성하면 201이고 user_id는 null이다`() {
        val story = storyRepository.save(Story(title = "스토리"))

        postChat(story.publicId, null).expectStatus().isCreated

        assertThat(storyPlaySessionRepository.findAll().first().userId).isNull()
    }

    private fun postChat(storyPublicId: UUID, authorization: String?): RestTestClient.ResponseSpec {
        val spec = restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header("Authorization", it) }
        return spec.body("""{"storyId":"$storyPublicId"}""").exchange()
    }

    // ---- POST /api/v1/stories/simple ----

    @Test
    fun `유효 토큰으로 간편 스토리를 생성하면 201과 함께 story user_id가 귀속된다`() {
        val user = saveUser()
        val storyline = persistStoryline()

        postSimpleStory(storyline, "Bearer ${validToken(user)}").expectStatus().isCreated

        val created = storyRepository.findAll().first()
        assertThat(created.userId).isEqualTo(user.id)
    }

    @Test
    fun `만료 토큰으로 간편 스토리를 생성하면 401이 아니라 201이고 story user_id는 null이다`() {
        val user = saveUser()
        val storyline = persistStoryline()

        postSimpleStory(storyline, "Bearer ${expiredToken(user)}").expectStatus().isCreated

        assertThat(storyRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `토큰 없이 간편 스토리를 생성하면 201이고 story user_id는 null이다`() {
        val storyline = persistStoryline()

        postSimpleStory(storyline, null).expectStatus().isCreated

        assertThat(storyRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `생성 요청이 익명이면 storyline 생성자의 user_id로 폴백하지 않는다 (귀속 스푸핑 차단)`() {
        // storyline 생성을 로그인 사용자(owner)가 한 상황(session.user_id=owner). 그 simpleCreationId만 알면
        // 익명으로 create해 owner의 user_id로 스토리가 기록되는 일이 없어야 한다 → 익명 create는 null 귀속.
        val owner = saveUser()
        val storyline = persistStorylineOwnedBy(owner.id)

        postSimpleStory(storyline, null).expectStatus().isCreated

        assertThat(storyRepository.findAll().first().userId).isNull()
    }

    private fun persistStoryline(): StoryCreationExample = persistStorylineSession(ownerId = null)

    private fun persistStorylineOwnedBy(ownerId: Long): StoryCreationExample = persistStorylineSession(ownerId)

    private fun persistStorylineSession(ownerId: Long?): StoryCreationExample {
        val session = creationSessionRepository.save(
            StoryCreationSession(userId = ownerId, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        return creationExampleRepository.save(
            StoryCreationExample(
                creationSession = session,
                exampleText = "예시 스토리라인",
                exampleOrder = 1,
            ),
        )
    }

    private fun postSimpleStory(storyline: StoryCreationExample, authorization: String?): RestTestClient.ResponseSpec {
        val spec = restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header("Authorization", it) }
        return spec
            .body(
                """{"simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
    }
}
