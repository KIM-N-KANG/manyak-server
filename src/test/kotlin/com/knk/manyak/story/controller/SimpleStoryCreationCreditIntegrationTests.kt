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

    // application.yml 기본값(스펙 §4-3-7, KNK-477 확정: 20)이 그대로 적용된다.
    private val storyCreationCost = 20L

    @BeforeEach
    fun setUp() {
        compileStoryCalls.set(0)
        failCompile = false
        databaseCleaner.cleanAll()
    }

    @Test
    fun `정지된 회원은 간편 제작이 403이고 compile이 호출되지 않으며 크레딧도 소모되지 않는다`() {
        val suspended = userRepository.save(User(nickname = "정지회원", status = UserStatus.SUSPENDED))
        val storyline = persistStorylineOwnedBy(suspended.id)

        postSimpleStory(storyline, "Bearer ${validToken(suspended)}")
            .expectStatus().isForbidden

        assertThat(compileStoryCalls.get()).isZero()
        assertThat(creditTransactionRepository.findAll()).isEmpty()
        assertThat(storyRepository.findAll()).isEmpty()
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
    fun `같은 세션을 두 번 실패하며 재시도해도 두 차감이 모두 환불되어 순 잔액이 그대로다`() {
        // 환불 멱등 키가 세션 단위면 두 번째 환불이 첫 환불 키와 충돌해 미적립 → 크레딧 유실(Codex P1).
        // 시도별 키면 각 차감이 자기 환불과 짝지어져, 재시도 실패에도 유실이 없다.
        val user = saveUser()
        creditWalletService.reward(user.id, 100, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)
        failCompile = true

        // 첫 시도: compile 실패 → 502. 세션은 STORY_CREATED에 이르지 못해 같은 simpleCreationId 재시도가 허용된다.
        postSimpleStory(storyline, "Bearer ${validToken(user)}").expectStatus().isEqualTo(502)
        // 둘째 시도: 같은 세션으로 재시도 → 또 compile 실패 → 502.
        postSimpleStory(storyline, "Bearer ${validToken(user)}").expectStatus().isEqualTo(502)

        // 두 번 차감(-10, -10)과 두 번 환불(+10, +10)이 모두 원장에 남고 순 잔액은 100 그대로.
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(100)
        val all = creditTransactionRepository.findAll()
        val deducts = all.filter { it.reason == CreditReason.STORY_CREATION }
        assertThat(deducts).hasSize(2)
        assertThat(deducts.map { it.amount }).containsExactly(-storyCreationCost, -storyCreationCost)
        val refunds = all.filter { it.reason == CreditReason.REFUND }
        assertThat(refunds).hasSize(2)
        assertThat(refunds.map { it.amount }).containsExactly(storyCreationCost, storyCreationCost)
        // 두 환불의 멱등 키가 서로 달라야(시도별) 둘 다 실제 적립된다.
        assertThat(refunds.mapNotNull { it.idempotencyKey }.distinct()).hasSize(2)
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

    @Autowired
    private lateinit var b13GuestTrialLimitService: com.knk.manyak.credit.service.GuestTrialLimitService

    // B13(스펙 §4-3-7): 회원도 잔여 체험을 크레딧보다 먼저 소진한다. 크레딧 경로를 검증하려면 회원 체험을 미리 소진시킨다.
    private fun saveUser(nickname: String = "manyak_user"): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE)).also { member ->
            while (b13GuestTrialLimitService.reserveMember(member.id, com.knk.manyak.credit.service.GuestTrialLimitService.Counter.STORY_CREATION)) { /* drain */ }
        }

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
