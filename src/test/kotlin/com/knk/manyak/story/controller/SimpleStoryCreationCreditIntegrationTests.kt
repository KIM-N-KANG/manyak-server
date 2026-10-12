package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.global.observability.AiTraceLink
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
        var lastCompileRequest: AiStoryCompileRequest? = null
        var skipImages = false
    }

    @TestConfiguration
    class FakeAiClientConfig {
        @Bean
        @Primary
        fun fakeStoryAiClient(): StoryAiClient = object : StoryAiClient {
            override fun createStorylines(request: AiStorylinesRequest, traceLink: AiTraceLink): AiStorylinesResponse =
                AiStorylinesResponse(stories = emptyList(), meta = AiResponseMeta())

            override fun compileStory(request: AiStoryCompileRequest, traceLink: AiTraceLink): AiStoryCompileResponse {
                compileStoryCalls.incrementAndGet()
                lastCompileRequest = request
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
                    thumbnailImage = if (skipImages) com.knk.manyak.story.client.AiThumbnailImage(
                        imageName = "썸네일_기본", contentType = "image/webp", error = "skipped",
                    ) else null,
                    characterImages = emptyList(),
                    characterIntroductions = if (skipImages) listOf(com.knk.manyak.story.client.AiCharacterIntroduction("아린", "동료를 지키는 기사")) else emptyList(),
                    characterAppearances = if (skipImages) listOf(com.knk.manyak.story.client.AiCharacterAppearance(name = "아린", hair = "검은 머리")) else emptyList(),
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

    @Autowired private lateinit var creditPolicyService: CreditPolicyService

    // 수치는 팀이 조정하는 정책값이라 리터럴로 박지 않고 해석 결과를 그대로 쓴다(KNK-1056).
    // 오버라이드 행이 없으면 application.yml 기본값이다.
    private val storyCreationCost: Long get() = creditPolicyService.amountOf(CreditPolicyKey.STORY_CREATION_COST)

    // 재시도 2회분 차감을 견디는 충분한 잔액.
    private val seedBalance: Long get() = storyCreationCost * 5

    @BeforeEach
    fun setUp() {
        compileStoryCalls.set(0)
        failCompile = false
        lastCompileRequest = null
        skipImages = false
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
        creditWalletService.reward(user.id, seedBalance, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)

        postSimpleStory(storyline, "Bearer ${validToken(user)}").expectStatus().isCreated

        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance - storyCreationCost)
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
            // 크레딧 부족은 게스트 한도와 구분되는 code로 온다(KNK-524).
            .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_CREDIT")

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
        creditWalletService.reward(user.id, seedBalance, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)
        failCompile = true

        postSimpleStory(storyline, "Bearer ${validToken(user)}")
            .expectStatus().isEqualTo(502)

        // 선차감과 환불이 모두 원장에 남고 순 잔액은 시드 그대로.
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance)
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
        creditWalletService.reward(user.id, seedBalance, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)
        failCompile = true

        // 첫 시도: compile 실패 → 502. 세션은 STORY_CREATED에 이르지 못해 같은 simpleCreationId 재시도가 허용된다.
        postSimpleStory(storyline, "Bearer ${validToken(user)}").expectStatus().isEqualTo(502)
        // 둘째 시도: 같은 세션으로 재시도 → 또 compile 실패 → 502.
        postSimpleStory(storyline, "Bearer ${validToken(user)}").expectStatus().isEqualTo(502)

        // 두 번 차감과 두 번 환불이 모두 원장에 남고 순 잔액은 시드 그대로.
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance)
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

    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var redis: org.springframework.data.redis.core.StringRedisTemplate
    @Autowired private lateinit var characters: com.knk.manyak.story.repository.StoryCharacterRepository
    @Autowired private lateinit var characterImages: com.knk.manyak.story.repository.StoryCharacterImageRepository

    private fun seedStyle(code: String, order: Int = 1, active: Boolean = true) {
        jdbc.update("INSERT INTO art_styles (code, name, description, sort_order, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            code, "이름 $code", "설명 $code", order, active)
    }

    @Test
    fun `게스트 화풍 목록은 활성 행을 순서대로 표시 정보와 null 미리보기로 반환한다`() {
        seedStyle("NONE", 11)
        seedStyle("ANIME", 2)
        seedStyle("HIDDEN", 2, false)
        for (token in listOf(null, "Bearer forged.token")) {
            val spec = restTestClient.get().uri("/api/v1/stories/art-styles")
            token?.let { spec.header("Authorization", it) }
            spec.exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].code").isEqualTo("ANIME")
                .jsonPath("$[0].name").isEqualTo("이름 ANIME")
                .jsonPath("$[0].description").isEqualTo("설명 ANIME")
                .jsonPath("$[0].previewImageUrl").isEqualTo(null)
                .jsonPath("$[1].code").isEqualTo("NONE")
        }
        jdbc.update("UPDATE art_styles SET sort_order = 1, name = '바뀐 이름', preview_image_url = 'https://example.com/none.webp' WHERE code = 'NONE'")
        restTestClient.get().uri("/api/v1/stories/art-styles").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$[0].name").isEqualTo("바뀐 이름")
            .jsonPath("$[0].previewImageUrl").isEqualTo("https://example.com/none.webp")
    }

    @Test
    fun `화풍 누락과 null은 카탈로그 시드 없이 AI art_style ANIME으로 전달한다`() {
        for (field in listOf("", ",\"artStyle\":null")) {
            databaseCleaner.cleanAll()
            postSimpleStory(persistStoryline(), null, field).expectStatus().isCreated
            val wire = mapper.readTree(mapper.writeValueAsString(lastCompileRequest!!))
            assertThat(wire["art_style"].asText()).isEqualTo("ANIME")
            assertThat(wire.has("artStyle")).isFalse()
        }
    }

    @Test
    fun `활성 화풍 코드는 변환 없이 AI art_style로 전달한다`() {
        seedStyle("SEMI_REAL")
        postSimpleStory(persistStoryline(), null, ",\"artStyle\":\"SEMI_REAL\"").expectStatus().isCreated
        assertThat(mapper.readTree(mapper.writeValueAsString(lastCompileRequest!!))["art_style"].asText()).isEqualTo("SEMI_REAL")
    }

    @Test
    fun `완료된 요청은 선택 화풍이 비활성화되어도 같은 결과를 재생하고 추가 차감하지 않는다`() {
        seedStyle("SEMI_REAL")
        val user = saveUser()
        creditWalletService.reward(user.id, seedBalance, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)
        val authorization = "Bearer ${validToken(user)}"
        val requestId = java.util.UUID.randomUUID()
        val first = postSimpleStory(storyline, authorization, ",\"artStyle\":\"SEMI_REAL\"", requestId)
            .expectStatus().isCreated.expectBody().returnResult().responseBody!!
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance - storyCreationCost)
        val transactions = creditTransactionRepository.count()
        val trialCounters = redis.keys("*trial:*").associateWith { redis.opsForValue().get(it) }

        jdbc.update("UPDATE art_styles SET is_active = false WHERE code = 'SEMI_REAL'")
        val replay = postSimpleStory(storyline, authorization, ",\"artStyle\":\"SEMI_REAL\"", requestId)
            .expectStatus().isCreated.expectBody().returnResult().responseBody!!

        assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(first))
        assertThat(compileStoryCalls.get()).isEqualTo(1)
        assertThat(storyRepository.count()).isEqualTo(1)
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance - storyCreationCost)
        assertThat(creditTransactionRepository.count()).isEqualTo(transactions)
        assertThat(redis.keys("*trial:*").associateWith { redis.opsForValue().get(it) }).isEqualTo(trialCounters)
    }

    @Autowired private lateinit var requests: com.knk.manyak.story.repository.StoryCreationRequestRepository

    @Test
    fun `오래된 PENDING 회수는 화풍이 비활성화되어도 저장된 스토리를 재구성한다`() {
        seedStyle("SEMI_REAL")
        val user = saveUser()
        creditWalletService.reward(user.id, seedBalance, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val storyline = persistStorylineOwnedBy(user.id)
        val authorization = "Bearer ${validToken(user)}"
        val requestId = java.util.UUID.randomUUID()
        val first = postSimpleStory(storyline, authorization, ",\"artStyle\":\"SEMI_REAL\"", requestId)
            .expectStatus().isCreated.expectBody().returnResult().responseBody!!
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance - storyCreationCost)
        val transactions = creditTransactionRepository.count()
        val trialCounters = redis.keys("*trial:*").associateWith { redis.opsForValue().get(it) }

        // 기존 회수 테스트처럼 요청 행만 지우고 aged PENDING으로 다시 심는다(@PreUpdate 방지).
        requests.delete(requests.findByRequestId(requestId)!!)
        requests.flush()
        requests.saveAndFlush(com.knk.manyak.story.entity.StoryCreationRequest(
            requestId = requestId,
            userId = user.id,
            stage = com.knk.manyak.story.entity.StoryCreationStage.STORY_COMPLETION,
            status = com.knk.manyak.story.entity.StoryCreationRequestStatus.PENDING,
            updatedAt = java.time.Instant.now().minusSeconds(600),
        ))
        val timerCounts = listOf("success", "failure", "rejected").associateWith {
            meterRegistry.find("manyak.story.creation.duration").tag("outcome", it).timer()?.count() ?: 0L
        }
        jdbc.update("UPDATE art_styles SET is_active = false WHERE code = 'SEMI_REAL'")
        val replay = postSimpleStory(storyline, authorization, ",\"artStyle\":\"SEMI_REAL\"", requestId)
            .expectStatus().isCreated.expectBody().returnResult().responseBody!!

        val reconciled = requests.findByRequestId(requestId)!!
        assertThat(reconciled.status).isEqualTo(com.knk.manyak.story.entity.StoryCreationRequestStatus.COMPLETED)
        assertThat(reconciled.resultJson).isNotNull()
        assertThat(listOf("success", "failure", "rejected").associateWith {
            meterRegistry.find("manyak.story.creation.duration").tag("outcome", it).timer()?.count() ?: 0L
        }).isEqualTo(timerCounts)
        assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(first))
        assertThat(compileStoryCalls.get()).isEqualTo(1)
        assertThat(storyRepository.count()).isEqualTo(1)
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance - storyCreationCost)
        assertThat(creditTransactionRepository.count()).isEqualTo(transactions)
        assertThat(redis.keys("*trial:*").associateWith { redis.opsForValue().get(it) }).isEqualTo(trialCounters)
    }

    @Autowired private lateinit var meterRegistry: io.micrometer.core.instrument.MeterRegistry

    @Test
    fun `무효 화풍 400은 story_creation_duration rejected를 한 번 올린다`() {
        fun count(outcome: String): Long =
            meterRegistry.find("manyak.story.creation.duration").tag("outcome", outcome).timer()?.count() ?: 0L
        val beforeRejected = count("rejected")
        val beforeFailure = count("failure")
        val beforeSuccess = count("success")

        postSimpleStory(persistStoryline(), null, ",\"artStyle\":\"UNKNOWN\"").expectStatus().isBadRequest

        assertThat(count("rejected")).isEqualTo(beforeRejected + 1)
        assertThat(count("failure")).isEqualTo(beforeFailure)
        assertThat(count("success")).isEqualTo(beforeSuccess)
        assertThat(compileStoryCalls.get()).isZero()
    }

    @Test
    fun `미등록 비활성 빈 화풍은 회원 차감과 게스트 및 회원 체험 예약과 AI 호출 전에 400이다`() {
        seedStyle("HIDDEN", active = false)
        for (style in listOf("UNKNOWN", "HIDDEN", "", "ANIME", "anime", " ANIME ")) {
            for (mode in listOf("guest", "paid", "trial")) {
                val user = when (mode) {
                    "paid" -> saveUser()
                    "trial" -> userRepository.save(User(nickname = "체험회원", status = UserStatus.ACTIVE))
                    else -> null
                }
                if (user != null) creditWalletService.reward(user.id, seedBalance, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
                val beforeKeys = redis.keys("*trial:*").associateWith { redis.opsForValue().get(it) }
                val beforeTransactions = creditTransactionRepository.count()
                postSimpleStory(persistStorylineSession(user?.id), user?.let { "Bearer ${validToken(it)}" },
                    ",\"artStyle\":\"$style\"").expectStatus().isBadRequest
                assertThat(compileStoryCalls.get()).isZero()
                assertThat(creditTransactionRepository.count()).isEqualTo(beforeTransactions)
                assertThat(redis.keys("*trial:*").associateWith { redis.opsForValue().get(it) }).isEqualTo(beforeKeys)
                if (user != null) assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance)
            }
        }
        assertThat(storyRepository.findAll()).isEmpty()
    }

    @Test
    fun `NONE과 skipped 표지는 스토리를 표지 없이 저장하고 기존 제작가를 차감한다`() {
        seedStyle("NONE")
        skipImages = true
        val user = saveUser()
        creditWalletService.reward(user.id, seedBalance, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        postSimpleStory(persistStorylineOwnedBy(user.id), "Bearer ${validToken(user)}", ",\"artStyle\":\"NONE\"")
            .expectStatus().isCreated
        assertThat(mapper.readTree(mapper.writeValueAsString(lastCompileRequest!!))["art_style"].asText()).isEqualTo("NONE")
        val story = storyRepository.findAll().single()
        assertThat(story.title).isEqualTo("생성된 스토리")
        assertThat(story.thumbnailImageKey).isNull()
        assertThat(story.thumbnailImageUrl).isNull()
        val character = characters.findAll().single()
        assertThat(character.name).isEqualTo("아린")
        assertThat(character.description).isEqualTo("동료를 지키는 기사")
        assertThat(character.hair).isEqualTo("검은 머리")
        assertThat(character.imageUrl).isNull()
        assertThat(characterImages.findAll()).isEmpty()
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(seedBalance - storyCreationCost)
        assertThat(creditTransactionRepository.findAll().filter { it.reason == CreditReason.STORY_CREATION }.single().amount)
            .isEqualTo(-storyCreationCost)
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

    private fun postSimpleStory(storyline: StoryCreationStoryline, authorization: String?, artStyleJson: String = "", requestId: java.util.UUID = java.util.UUID.randomUUID()): RestTestClient.ResponseSpec {
        val spec = restTestClient.post()
            .uri("/api/v1/stories/simple")
            // 게스트 요청에 필요한 헤더(스펙 §4-3-7). 회원 요청에는 무해하다(회원은 이 헤더를 쓰지 않음).
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header("Authorization", it) }
        return spec
            .body(
                """{"requestId":"$requestId","simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]$artStyleJson}""",
            )
            .exchange()
    }
}
