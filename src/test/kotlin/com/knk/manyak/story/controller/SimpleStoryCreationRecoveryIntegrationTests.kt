package com.knk.manyak.story.controller

import com.knk.manyak.story.client.AiResponseMeta
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStoryItem
import com.knk.manyak.story.client.AiStoryMeta
import com.knk.manyak.story.client.AiStorySettings
import com.knk.manyak.story.client.AiStoryStartSettings
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.entity.StoryCreationRequest
import com.knk.manyak.story.entity.StoryCreationRequestStatus
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationStage
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * KNK-631: 백그라운드 스토리 생성 복귀 조회·멱등(스펙 §4-3-8).
 *
 * - requestId로 요청을 추적해, 응답을 못 받은 클라이언트가 GET /creation-requests/{requestId}로 결과를 되찾는다.
 * - 같은 requestId 재요청은 COMPLETED면 저장된 결과를 AI 재호출 없이 돌려주고, PENDING이면 409, FAILED면 재실행한다.
 * - 소유 주체(게스트 디바이스)만 복구 조회할 수 있다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimpleStoryCreationRecoveryIntegrationTests {

    companion object {
        val createStorylinesCalls = AtomicInteger(0)
        val compileStoryCalls = AtomicInteger(0)

        // true면 createStorylines가 예외를 던져 스토리라인 생성 실패(→ FAILED)를 강제한다.
        @Volatile
        var failStorylines = false
    }

    @TestConfiguration
    class FakeAiClientConfig {
        @Bean
        @Primary
        fun fakeStoryAiClient(): StoryAiClient = object : StoryAiClient {
            override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse {
                createStorylinesCalls.incrementAndGet()
                if (failStorylines) {
                    throw IllegalStateException("AI 스토리라인 생성 강제 실패")
                }
                return AiStorylinesResponse(
                    stories = (1..3).map { index ->
                        AiStoryItem(
                            id = index,
                            storyline = "생성 스토리라인 $index",
                            recommendedInfos = (1..3).map { "추천 정보 $index-$it" },
                        )
                    },
                    meta = AiResponseMeta(),
                )
            }

            override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse {
                compileStoryCalls.incrementAndGet()
                return AiStoryCompileResponse(
                    stories = AiStoryMeta(title = "복구된 스토리", oneLineIntro = "한 줄 소개", description = "설명"),
                    storySettings = AiStorySettings(
                        worldSetting = "세계관",
                        characterSetting = "캐릭터",
                        userRoleSetting = "역할",
                        ruleSetting = "규칙",
                    ),
                    storyStartSettings = AiStoryStartSettings(name = "시작", startSituation = "상황", prologue = "프롤로그"),
                    storySuggestedInputs = listOf("추천1"),
                    meta = AiResponseMeta(),
                )
            }
        }
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var simpleStoryCreationService: com.knk.manyak.story.service.SimpleStoryCreationService
    @Autowired private lateinit var requestRepository: StoryCreationRequestRepository
    @Autowired private lateinit var sessionRepository: StoryCreationSessionRepository
    @Autowired private lateinit var storylineRepository: StoryCreationStorylineRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner
    @Autowired private lateinit var deviceIdHasher: com.knk.manyak.global.observability.DeviceIdHasher
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider

    private val deviceA = "device-a"
    private val deviceB = "device-b"

    @BeforeEach
    fun setUp() {
        createStorylinesCalls.set(0)
        compileStoryCalls.set(0)
        failStorylines = false
        databaseCleaner.cleanAll()
    }

    @Test
    fun `존재하지 않는 requestId 복구 조회는 404`() {
        restTestClient.get()
            .uri("/api/v1/stories/simple/creation-requests/${UUID.randomUUID()}")
            .header("X-Manyak-Device-Id", deviceA)
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `requestId 없이 스토리라인 생성을 요청하면 400`() {
        val genre = seedGenreTag()

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", deviceA)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"selectedTagIds":[${genre.id}]}""")
            .exchange()
            .expectStatus().isBadRequest

        assertThat(createStorylinesCalls.get()).isZero()
    }

    @Test
    fun `스토리라인 생성 후 같은 requestId 복구 조회는 COMPLETED와 결과를 돌려준다`() {
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()

        postStorylines(requestId, genre.id, deviceA).expectStatus().isCreated

        restTestClient.get()
            .uri("/api/v1/stories/simple/creation-requests/$requestId")
            .header("X-Manyak-Device-Id", deviceA)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.stage").isEqualTo("STORYLINE_GENERATION")
            .jsonPath("$.status").isEqualTo("COMPLETED")
            .jsonPath("$.result.storylines.length()").isEqualTo(3)
            .jsonPath("$.result.simpleCreationId").isNumber
    }

    @Test
    fun `같은 requestId 재요청은 AI 재호출 없이 저장된 결과를 돌려준다`() {
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()

        val first = postStorylines(requestId, genre.id, deviceA)
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.simpleCreationId").isNumber
            .returnResult()
        val second = postStorylines(requestId, genre.id, deviceA)
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        // AI는 첫 요청에서만 호출되고 재요청은 저장된 결과를 그대로 돌려준다.
        assertThat(createStorylinesCalls.get()).isEqualTo(1)
        // 중복 세션이 만들어지지 않는다(진행 세션 1개).
        assertThat(sessionRepository.count()).isEqualTo(1)
        assertThat(String(first.responseBody!!)).isEqualTo(String(second.responseBody!!))
    }

    @Test
    fun `다른 디바이스의 복구 조회는 404`() {
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()
        postStorylines(requestId, genre.id, deviceA).expectStatus().isCreated

        restTestClient.get()
            .uri("/api/v1/stories/simple/creation-requests/$requestId")
            .header("X-Manyak-Device-Id", deviceB)
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `진행 중(PENDING) 요청을 같은 requestId로 재POST하면 409이고 AI를 호출하지 않는다`() {
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()
        // 아직 완료되지 않은(PENDING) 요청 행을 미리 심어, 재요청이 진행 중 충돌로 거부되는지 검증한다.
        requestRepository.saveAndFlush(
            StoryCreationRequest(
                requestId = requestId,
                deviceIdHash = deviceIdHasher.hash(deviceA),
                stage = StoryCreationStage.STORYLINE_GENERATION,
                status = StoryCreationRequestStatus.PENDING,
            ),
        )

        postStorylines(requestId, genre.id, deviceA).expectStatus().isEqualTo(409)

        assertThat(createStorylinesCalls.get()).isZero()
    }

    @Test
    fun `다른 단계에서 쓴 requestId를 재사용하면 409`() {
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()
        postStorylines(requestId, genre.id, deviceA).expectStatus().isCreated

        // 같은 requestId를 스토리 완성 단계에 재사용하면 단계 불일치로 409(저장된 응답을 다른 타입으로 역직렬화하는 500 방지).
        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", deviceA)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"$requestId","simpleCreationId":1,"storylineId":1,"additionalInfos":[]}""")
            .exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `device 없는 게스트 요청은 요청 행을 남기지 않고 올바른 device로 재시도하면 생성된다`() {
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()

        // device 헤더 없는 게스트 요청은 400이고, 소유자 없는 요청 행을 남기지 않는다(재시도가 409로 막히지 않도록).
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"$requestId","selectedTagIds":[${genre.id}]}""")
            .exchange()
            .expectStatus().isBadRequest
        assertThat(requestRepository.findByRequestId(requestId)).isNull()

        // 같은 requestId를 올바른 device로 재시도하면 409로 막히지 않고 정상 생성된다.
        postStorylines(requestId, genre.id, deviceA).expectStatus().isCreated
        assertThat(createStorylinesCalls.get()).isEqualTo(1)
    }

    @Test
    fun `회원 소유 세션은 만료 토큰으로 보인 첫 시도가 회원 재시도를 409로 막지 않는다`() {
        // Codex P2-6: 소유자를 요청 인증 신원이 아니라 세션 소유권으로 정해, 만료 토큰(게스트로 보임) 첫 시도가 남긴
        // 행이 갱신 토큰 재시도를 소유 불일치 409로 오염하지 않는지 검증한다.
        val member = userRepository.save(User(nickname = "회원제작자", status = UserStatus.ACTIVE))
        val session = sessionRepository.save(
            StoryCreationSession(userId = member.id, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val storyline = storylineRepository.save(
            StoryCreationStoryline(creationSession = session, storylineText = "회원 스토리라인", storylineOrder = 1),
        )
        val requestId = UUID.randomUUID()

        // 만료·누락 토큰(게스트로 보임)으로 회원 소유 세션을 완료 시도 → 소유권 불일치 403. 요청 행은 세션 회원 소유로 남는다.
        postSimpleStory(requestId, session.id, storyline.id, deviceId = deviceA, authorization = null)
            .expectStatus().isForbidden

        // 토큰을 갱신해 같은 requestId로 재시도하면 409로 막히지 않고 정상 완료된다(멱등 키 오염 없음).
        postSimpleStory(
            requestId,
            session.id,
            storyline.id,
            deviceId = null,
            authorization = "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}",
        ).expectStatus().isCreated
    }

    @Test
    fun `실패한 요청은 같은 requestId로 재실행된다`() {
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()
        failStorylines = true
        postStorylines(requestId, genre.id, deviceA).expectStatus().isEqualTo(502)
        assertThat(requestRepository.findByRequestId(requestId)?.status)
            .isEqualTo(StoryCreationRequestStatus.FAILED)

        failStorylines = false
        postStorylines(requestId, genre.id, deviceA).expectStatus().isCreated

        // 첫 실패 + 재실행으로 AI가 두 번 호출된다(FAILED는 재실행 허용).
        assertThat(createStorylinesCalls.get()).isEqualTo(2)
        assertThat(requestRepository.findByRequestId(requestId)?.status)
            .isEqualTo(StoryCreationRequestStatus.COMPLETED)
    }

    @Test
    fun `같은 requestId 동시 요청은 한 번만 생성하고 이중 실행되지 않는다`() {
        // Codex P2: 동시 삽입 경합·동시 FAILED 재실행이 이중 생성으로 새지 않는지 검증한다(스케줄링과 무관한 불변식).
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()
        val threads = 8
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val startGate = java.util.concurrent.CountDownLatch(1)
        val successIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val conflicts = java.util.concurrent.atomic.AtomicInteger(0)

        val futures = (1..threads).map {
            pool.submit {
                startGate.await()
                try {
                    val response = simpleStoryCreationService.generateSimpleStorylines(
                        GenerateSimpleStorylinesRequest(requestId = requestId, selectedTagIds = listOf(genre.id)),
                        userId = null,
                        deviceId = deviceA,
                    )
                    successIds.add(response.simpleCreationId)
                } catch (exception: org.springframework.web.server.ResponseStatusException) {
                    if (exception.statusCode.value() == 409) conflicts.incrementAndGet() else throw exception
                }
            }
        }
        startGate.countDown()
        futures.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        pool.shutdown()

        // AI는 정확히 한 번만 호출되고 진행 세션도 하나만 생성된다(이중 실행 없음).
        assertThat(createStorylinesCalls.get()).isEqualTo(1)
        assertThat(sessionRepository.count()).isEqualTo(1)
        // 성공 응답은 모두 같은 세션(멱등 replay)이고, 성공 + 409 = 전체 스레드 수다.
        assertThat(successIds.distinct()).hasSize(1)
        assertThat(successIds.size + conflicts.get()).isEqualTo(threads)
    }

    @Test
    fun `스토리 완성 요청도 requestId로 복구된다`() {
        val storyline = seedGeneratedStoryline()
        val requestId = UUID.randomUUID()

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", deviceA)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"$requestId","simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
            .expectStatus().isCreated

        restTestClient.get()
            .uri("/api/v1/stories/simple/creation-requests/$requestId")
            .header("X-Manyak-Device-Id", deviceA)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.stage").isEqualTo("STORY_COMPLETION")
            .jsonPath("$.status").isEqualTo("COMPLETED")
            .jsonPath("$.result.title").isEqualTo("복구된 스토리")
            .jsonPath("$.result.id").isNotEmpty
    }

    private fun postStorylines(requestId: UUID, genreTagId: Long, deviceId: String): RestTestClient.ResponseSpec =
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", deviceId)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"$requestId","selectedTagIds":[$genreTagId]}""")
            .exchange()

    private fun postSimpleStory(
        requestId: UUID,
        simpleCreationId: Long,
        storylineId: Long,
        deviceId: String?,
        authorization: String?,
    ): RestTestClient.ResponseSpec {
        val spec = restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
        deviceId?.let { spec.header("X-Manyak-Device-Id", it) }
        authorization?.let { spec.header("Authorization", it) }
        return spec
            .body("""{"requestId":"$requestId","simpleCreationId":$simpleCreationId,"storylineId":$storylineId,"additionalInfos":[]}""")
            .exchange()
    }

    private fun seedGenreTag() = tagRepository.save(
        com.knk.manyak.story.entity.StoryCreationTag(
            category = com.knk.manyak.story.dto.SimpleStoryTagCategory.GENRE,
            name = "판타지",
            tagSource = com.knk.manyak.story.entity.StoryCreationTagSource.PREDEFINED,
            sortOrder = 1,
            isActive = true,
        ),
    )

    private fun seedGeneratedStoryline(): StoryCreationStoryline {
        val session = sessionRepository.save(
            StoryCreationSession(userId = null, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        return storylineRepository.save(
            StoryCreationStoryline(
                creationSession = session,
                storylineText = "예시 스토리라인",
                storylineOrder = 1,
            ),
        )
    }

    @Autowired private lateinit var tagRepository: com.knk.manyak.story.repository.StoryCreationTagRepository
}
