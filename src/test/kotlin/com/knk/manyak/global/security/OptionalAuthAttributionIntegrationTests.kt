package com.knk.manyak.global.security

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
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
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
        // fake AI의 compileStory 호출 횟수. finalize 소유권 거부가 AI 호출 '전'에 일어남을 검증한다(Codex #76 P2).
        val compileStoryCalls = AtomicInteger(0)

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

            override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse {
                compileStoryCalls.incrementAndGet()
                return AiStoryCompileResponse(
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
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var creationSessionRepository: StoryCreationSessionRepository

    @Autowired
    private lateinit var creationStorylineRepository: StoryCreationStorylineRepository

    @Autowired
    private lateinit var creditWalletService: CreditWalletService

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        compileStoryCalls.set(0)
        databaseCleaner.cleanAll()
    }

    // 회원이 유효 토큰으로 간편 스토리를 완료하면 KNK-398 선차감이 걸린다. 이 클래스는 귀속(KNK-286)을 검증하므로,
    // 차감이 성공하도록 넉넉히 충전해 둔다(잔액 부족 402는 SimpleStoryCreationCreditIntegrationTests가 별도로 검증).
    private fun fundWallet(user: User) {
        creditWalletService.reward(user.id, 1_000, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
    }

    private fun saveUser(nickname: String = "manyak_user"): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

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

        assertThat(storyChatRepository.findAll().first().userId).isEqualTo(user.id)
    }

    @Test
    fun `만료 토큰으로 채팅을 생성하면 401이 아니라 201이고 user_id는 null이다`() {
        val user = saveUser()
        val story = storyRepository.save(Story(title = "스토리"))

        postChat(story.publicId, "Bearer ${expiredToken(user)}").expectStatus().isCreated

        assertThat(storyChatRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `위조 토큰으로 채팅을 생성하면 401이 아니라 201이고 user_id는 null이다`() {
        val story = storyRepository.save(Story(title = "스토리"))

        postChat(story.publicId, "Bearer $forgedToken").expectStatus().isCreated

        assertThat(storyChatRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `토큰 없이 채팅을 생성하면 201이고 user_id는 null이다`() {
        val story = storyRepository.save(Story(title = "스토리"))

        postChat(story.publicId, null).expectStatus().isCreated

        assertThat(storyChatRepository.findAll().first().userId).isNull()
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
        fundWallet(user)
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
    fun `소유 세션을 같은 사용자가 완료하면 201과 함께 owner에 귀속된다`() {
        val owner = saveUser()
        fundWallet(owner)
        val storyline = persistStorylineOwnedBy(owner.id)

        postSimpleStory(storyline, "Bearer ${validToken(owner)}").expectStatus().isCreated

        assertThat(storyRepository.findAll().first().userId).isEqualTo(owner.id)
    }

    @Test
    fun `소유 세션을 다른 로그인 사용자가 완료하려 하면 403이다 (세션 가로채기 차단)`() {
        val owner = saveUser("owner")
        val attacker = saveUser("attacker")
        val storyline = persistStorylineOwnedBy(owner.id)

        postSimpleStory(storyline, "Bearer ${validToken(attacker)}").expectStatus().isForbidden

        // 소유권 거부는 AI 호출(비용) 전에 일어난다 → compileStory 미호출, Story도 미저장.
        assertThat(compileStoryCalls.get()).isZero()
        assertThat(storyRepository.findAll()).isEmpty()
    }

    @Test
    fun `소유 세션을 익명(만료·무토큰)으로 완료하려 하면 403이다`() {
        // 소유 세션을 익명으로 떨어뜨려 완료하는 것도 막는다 — 본인 토큰 재인증이 필요하다.
        val owner = saveUser()
        val storyline = persistStorylineOwnedBy(owner.id)

        postSimpleStory(storyline, null).expectStatus().isForbidden

        assertThat(storyRepository.findAll()).isEmpty()
    }

    // ---- PUT /api/v1/stories/simple/storylines/{id}/rating (소유권) ----

    @Test
    fun `소유 세션 스토리라인을 같은 사용자가 평가하면 200이다`() {
        val owner = saveUser()
        val storyline = persistStorylineOwnedBy(owner.id)

        putRating(storyline.id, "Bearer ${validToken(owner)}").expectStatus().isOk
    }

    @Test
    fun `소유 세션 스토리라인을 다른 사용자가 평가하려 하면 403이다`() {
        val owner = saveUser("owner")
        val attacker = saveUser("attacker")
        val storyline = persistStorylineOwnedBy(owner.id)

        putRating(storyline.id, "Bearer ${validToken(attacker)}").expectStatus().isForbidden
    }

    @Test
    fun `익명 세션 스토리라인은 토큰 없이 평가해도 200이다`() {
        val storyline = persistStoryline()

        putRating(storyline.id, null).expectStatus().isOk
    }

    @Test
    fun `익명 세션을 로그인 사용자가 완료하면 세션 소유자가 그 사용자가 되어 이후 평가가 보호된다`() {
        val owner = saveUser("owner")
        fundWallet(owner)
        val storyline = persistStoryline() // 익명 세션

        // 익명 세션을 owner가 완료(claim)
        postSimpleStory(storyline, "Bearer ${validToken(owner)}").expectStatus().isCreated

        // 세션 소유자가 owner로 박혀, 다른 사용자는 그 스토리라인을 평가하지 못한다(403).
        assertThat(creationSessionRepository.findAll().first().userId).isEqualTo(owner.id)
        val attacker = saveUser("attacker")
        putRating(storyline.id, "Bearer ${validToken(attacker)}").expectStatus().isForbidden
    }

    private fun putRating(storylineId: Long, authorization: String?): RestTestClient.ResponseSpec {
        val spec = restTestClient.put()
            .uri("/api/v1/stories/simple/storylines/$storylineId/rating")
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header("Authorization", it) }
        return spec.body("""{"rating":"GOOD"}""").exchange()
    }

    private fun persistStoryline(): StoryCreationStoryline = persistStorylineSession(ownerId = null)

    private fun persistStorylineOwnedBy(ownerId: Long): StoryCreationStoryline = persistStorylineSession(ownerId)

    private fun persistStorylineSession(ownerId: Long?): StoryCreationStoryline {
        val session = creationSessionRepository.save(
            StoryCreationSession(userId = ownerId, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        return creationStorylineRepository.save(
            StoryCreationStoryline(
                creationSession = session,
                storylineText = "예시 스토리라인",
                storylineOrder = 1,
            ),
        )
    }

    private fun postSimpleStory(storyline: StoryCreationStoryline, authorization: String?): RestTestClient.ResponseSpec {
        val spec = restTestClient.post()
            .uri("/api/v1/stories/simple")
            // 게스트 요청에 필요한 헤더(스펙 §4-3-7). 회원 요청에는 무해하다(회원은 이 헤더를 쓰지 않음).
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header("Authorization", it) }
        return spec
            .body(
                """{"simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
    }
}
