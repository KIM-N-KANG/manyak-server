package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.story.client.AiResponseMeta
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStoryMeta
import com.knk.manyak.story.client.AiStorySettings
import com.knk.manyak.story.client.AiStoryStartSettings
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
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
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * KNK-398: 간편 제작 크레딧 차감(스펙 §4-3-7 소모).
 *
 * - 회원(유효 토큰): compile 시작 전에 STORY_CREATION 1회 선차감, 성공 시 유지.
 * - 회원 잔액 부족: 동기 402, compile 미호출, 잔액 불변.
 * - compile 실패: REFUND로 전액 환불(순 잔액 불변), compile 실패는 그대로 502.
 * - 게스트(토큰 없음/무효): 차감 없음(기존 동작 유지).
 *
 * AI 클라이언트는 @Primary 페이크로 대체하며, compile 실패는 페이크 토글로 강제한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimpleStoryCreationCreditIntegrationTests {

    companion object {
        // compileStory 호출 횟수. 잔액 부족 시 compile이 호출되지 않음을 검증한다(선차감 후 402).
        val compileStoryCalls = AtomicInteger(0)

        // true면 compileStory가 예외를 던져 compile 실패(→ 환불)를 강제한다.
        @Volatile
        var failCompile = false
    }

    @TestConfiguration
    class FakeAiClientConfig {
        @Bean
        @Primary
        fun fakeStoryAiClient(): StoryAiClient = object : StoryAiClient {
            override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse =
                AiStorylinesResponse(stories = emptyList(), meta = AiResponseMeta())

            override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse {
                compileStoryCalls.incrementAndGet()
                if (failCompile) {
                    throw IllegalStateException("AI compile 강제 실패")
                }
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

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired private lateinit var sessionRepository: StoryCreationSessionRepository
    @Autowired private lateinit var storylineRepository: StoryCreationStorylineRepository
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    // application.yml을 건드리지 않으므로 @Value 기본값(10)이 적용된다.
    private val storyCreationCost = 10L

    @BeforeEach
    fun setUp() {
        compileStoryCalls.set(0)
        failCompile = false
        databaseCleaner.cleanAll()
    }

    @Test
    fun `회원이 충분한 잔액으로 간편 제작하면 201과 함께 잔액이 비용만큼 줄고 STORY_CREATION 원장 행이 남는다`() {
        val user = saveUser()
        creditWalletService.reward(user.id, 100, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)

        postSimpleStory(storyline, "Bearer ${validToken(user)}").expectStatus().isCreated

        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(100 - storyCreationCost)
        val consumption = creditTransactionRepository.findAll()
            .filter { it.reason == CreditReason.STORY_CREATION }
        assertThat(consumption).hasSize(1)
        assertThat(consumption.first().amount).isEqualTo(-storyCreationCost)
        assertThat(consumption.first().refType).isEqualTo("STORY")
        assertThat(consumption.first().refId).isEqualTo(storyline.creationSession.id)
        // 환불 행은 없어야 한다(성공 시 차감 유지).
        assertThat(creditTransactionRepository.findAll().none { it.reason == CreditReason.REFUND }).isTrue()
    }

    @Test
    fun `회원 잔액이 부족하면 402이고 compile은 호출되지 않으며 잔액이 그대로다`() {
        val user = saveUser()
        creditWalletService.reward(user.id, 5, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)

        postSimpleStory(storyline, "Bearer ${validToken(user)}")
            .expectStatus().isEqualTo(402)

        assertThat(compileStoryCalls.get()).isZero()
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(5)
        // 소모/환불 행이 없어야 한다(적립 1건만).
        assertThat(creditTransactionRepository.findAll().map { it.reason })
            .containsExactly(CreditReason.SIGNUP_REWARD)
        assertThat(storyRepository.findAll()).isEmpty()
    }

    @Test
    fun `compile이 실패하면 502이고 선차감이 전액 환불되어 순 잔액이 그대로다`() {
        val user = saveUser()
        creditWalletService.reward(user.id, 100, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)
        failCompile = true

        postSimpleStory(storyline, "Bearer ${validToken(user)}")
            .expectStatus().isEqualTo(502)

        // 선차감(-10)과 환불(+10)이 모두 원장에 남고 순 잔액은 100 그대로.
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(100)
        val all = creditTransactionRepository.findAll()
        val deduct = all.first { it.reason == CreditReason.STORY_CREATION }
        assertThat(deduct.amount).isEqualTo(-storyCreationCost)
        val refund = all.first { it.reason == CreditReason.REFUND }
        assertThat(refund.amount).isEqualTo(storyCreationCost)
        assertThat(refund.refType).isEqualTo("STORY")
        assertThat(refund.refId).isEqualTo(storyline.creationSession.id)
        // 스토리는 저장되지 않아야 한다.
        assertThat(storyRepository.findAll()).isEmpty()
    }

    @Test
    fun `게스트는 토큰 없이 간편 제작해도 차감되지 않고 크레딧 원장이 비어 있다`() {
        val storyline = persistStoryline()

        postSimpleStory(storyline, null).expectStatus().isCreated

        assertThat(creditTransactionRepository.findAll()).isEmpty()
        assertThat(storyRepository.findAll().first().userId).isNull()
    }

    @Test
    fun `무효 토큰(게스트)도 차감되지 않는다`() {
        val storyline = persistStoryline()

        postSimpleStory(storyline, "Bearer not-a-real-jwt.forged.token").expectStatus().isCreated

        assertThat(creditTransactionRepository.findAll()).isEmpty()
        assertThat(storyRepository.findAll().first().userId).isNull()
    }

    private fun saveUser(nickname: String = "manyak_user"): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun validToken(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    private fun persistStoryline(): StoryCreationStoryline = persistStorylineSession(ownerId = null)

    private fun persistStorylineOwnedBy(ownerId: Long): StoryCreationStoryline = persistStorylineSession(ownerId)

    private fun persistStorylineSession(ownerId: Long?): StoryCreationStoryline {
        val session = sessionRepository.save(
            StoryCreationSession(userId = ownerId, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        return storylineRepository.save(
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
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header("Authorization", it) }
        return spec
            .body(
                """{"simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
    }
}
