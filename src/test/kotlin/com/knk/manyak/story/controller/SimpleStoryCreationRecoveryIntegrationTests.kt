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
import java.time.Instant
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
    fun `게스트로 기록된 요청은 같은 디바이스 회원 재시도를 409로 막지 않고 결과를 replay한다`() {
        // Codex P2-7: 만료 토큰(게스트로 보임)으로 device 소유 행이 기록된 뒤, 토큰 갱신으로 같은 device의 회원이
        // 같은 requestId로 재시도하면 device 해시로 소유가 매칭돼 저장된 결과를 replay해야 한다(409 오염 없음).
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()
        postStorylines(requestId, genre.id, deviceA).expectStatus().isCreated

        val member = userRepository.save(User(nickname = "회원", status = UserStatus.ACTIVE))
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", deviceA)
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"$requestId","selectedTagIds":[${genre.id}]}""")
            .exchange()
            .expectStatus().isCreated

        // AI 재호출 없이 저장된 결과를 replay한다(멱등 유지).
        assertThat(createStorylinesCalls.get()).isEqualTo(1)
        assertThat(sessionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `회원 소유 요청은 같은 디바이스 게스트가 복구 조회로 가로챌 수 없다`() {
        // Codex P2-8: 회원 소유 행(userId 있음)은 디바이스 매칭이 소유를 우회하지 못한다(공유 기기·계정 전환 노출 방지).
        val genre = seedGenreTag()
        val member = userRepository.save(User(nickname = "회원", status = UserStatus.ACTIVE))
        val requestId = UUID.randomUUID()
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", deviceA)
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"$requestId","selectedTagIds":[${genre.id}]}""")
            .exchange()
            .expectStatus().isCreated

        // 같은 디바이스의 게스트(비인증)는 회원 소유 행을 복구 조회할 수 없다.
        restTestClient.get()
            .uri("/api/v1/stories/simple/creation-requests/$requestId")
            .header("X-Manyak-Device-Id", deviceA)
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `오래 PENDING인 요청은 재실행으로 회수된다`() {
        // Codex P2-9: PENDING 커밋 후 프로세스가 죽으면 영구 409가 되지 않도록, 임계값보다 오래된 PENDING은 재실행을 허용한다.
        val genre = seedGenreTag()
        val requestId = UUID.randomUUID()
        requestRepository.saveAndFlush(
            StoryCreationRequest(
                requestId = requestId,
                deviceIdHash = deviceIdHasher.hash(deviceA),
                stage = StoryCreationStage.STORYLINE_GENERATION,
                status = StoryCreationRequestStatus.PENDING,
                // 기본 임계값(300초)보다 오래된 PENDING = 실행 중 죽은 잔여로 간주.
                updatedAt = Instant.now().minusSeconds(600),
            ),
        )

        postStorylines(requestId, genre.id, deviceA).expectStatus().isCreated
        assertThat(createStorylinesCalls.get()).isEqualTo(1)
        assertThat(requestRepository.findByRequestId(requestId)?.status)
            .isEqualTo(StoryCreationRequestStatus.COMPLETED)
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
    fun `회원 세션에서 스토리 커밋 후 COMPLETED 마킹을 잃은 회수 재실행은 저장된 스토리를 재구성해 돌려준다`() {
        // KNK-635(P2-10): compileAndPersist가 story·session(STORY_CREATED)을 커밋한 뒤, 별도 트랜잭션의 COMPLETED
        // 마킹 커밋 전에 프로세스가 죽으면 요청 행은 result 없이 PENDING으로 남는다. 회수 재실행이 STORY_CREATED
        // 세션을 만나 409→FAILED가 되어 잃은 story id를 복구 못 하던 것을, session.storyId로 응답을 재구성해 돌려준다.
        // reconcile은 회원 소유 세션에만 적용한다(익명 세션은 소유 바인딩이 없어 재구성하지 않음 — Codex P1).
        val member = userRepository.save(User(nickname = "회원제작자", status = UserStatus.ACTIVE))
        val token = "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}"
        val session = sessionRepository.save(
            StoryCreationSession(userId = member.id, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val storyline = storylineRepository.save(
            StoryCreationStoryline(creationSession = session, storylineText = "회원 스토리라인", storylineOrder = 1),
        )
        val requestId = UUID.randomUUID()

        val firstBody = postSimpleStory(requestId, session.id, storyline.id, deviceId = null, authorization = token)
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.id").isNotEmpty
            .returnResult()

        // 크래시 창 재현: story·session은 STORY_CREATED로 유지하되, 요청 행은 COMPLETED 마킹을 잃고 오래된 PENDING으로 남은
        // 상태로 만든다. @PreUpdate가 UPDATE마다 updatedAt을 now()로 덮으므로, 기존 행을 지우고 생성자 INSERT로 stale PENDING을 심는다
        // (INSERT는 @PreUpdate를 발동하지 않아, 재실행 회수 임계값을 넘긴 updatedAt이 보존된다). 소유는 회원(userId)으로 심는다.
        requestRepository.delete(requestRepository.findByRequestId(requestId)!!)
        requestRepository.flush()
        requestRepository.saveAndFlush(
            StoryCreationRequest(
                requestId = requestId,
                userId = member.id,
                stage = StoryCreationStage.STORY_COMPLETION,
                status = StoryCreationRequestStatus.PENDING,
                updatedAt = Instant.now().minusSeconds(600),
            ),
        )

        // 같은 requestId 회수 재실행: 409/FAILED가 아니라 저장된 스토리를 그대로 돌려준다(AI 재호출·중복 스토리·중복 과금 없음).
        val secondBody = postSimpleStory(requestId, session.id, storyline.id, deviceId = null, authorization = token)
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        // 저장된 스토리를 재구성해 첫 응답과 동일하게 돌려준다.
        assertThat(String(secondBody.responseBody!!)).isEqualTo(String(firstBody.responseBody!!))
        // AI compile은 첫 요청에서만 호출되고 스토리도 하나만 존재한다(재구성 경로는 AI·저장을 다시 타지 않는다).
        assertThat(compileStoryCalls.get()).isEqualTo(1)
        assertThat(storyRepository.count()).isEqualTo(1)
        // 요청 행은 회수 재실행으로 다시 COMPLETED가 되고 결과가 채워진다.
        val reconciled = requestRepository.findByRequestId(requestId)!!
        assertThat(reconciled.status).isEqualTo(StoryCreationRequestStatus.COMPLETED)
        assertThat(reconciled.resultJson).isNotNull()
    }

    @Test
    fun `게스트 세션도 이 세션을 만든 requestId 회수면 저장된 스토리를 재구성해 돌려준다`() {
        // KNK-644: 익명(게스트) 세션도 creationRequestId 바인딩으로 "이 세션을 만든 그 요청"의 회수면 안전하게 재구성 복구한다.
        val storyline = seedGeneratedStoryline() // userId=null 게스트 세션
        val requestId = UUID.randomUUID()
        val firstBody = postSimpleStory(requestId, storyline.creationSession.id, storyline.id, deviceA, null)
            .expectStatus().isCreated
            .expectBody().jsonPath("$.id").isNotEmpty
            .returnResult()

        // 크래시 창 재현(같은 requestId, 게스트 소유 aged PENDING).
        requestRepository.delete(requestRepository.findByRequestId(requestId)!!)
        requestRepository.flush()
        requestRepository.saveAndFlush(
            StoryCreationRequest(
                requestId = requestId,
                deviceIdHash = deviceIdHasher.hash(deviceA),
                stage = StoryCreationStage.STORY_COMPLETION,
                status = StoryCreationRequestStatus.PENDING,
                updatedAt = Instant.now().minusSeconds(600),
            ),
        )

        // 같은 requestId 회수 재실행: 바인딩이 일치하므로 저장된 스토리를 그대로 돌려준다(AI 재호출·중복 스토리 없음).
        val secondBody = postSimpleStory(requestId, storyline.creationSession.id, storyline.id, deviceA, null)
            .expectStatus().isCreated
            .expectBody().returnResult()

        assertThat(String(secondBody.responseBody!!)).isEqualTo(String(firstBody.responseBody!!))
        assertThat(compileStoryCalls.get()).isEqualTo(1)
        assertThat(storyRepository.count()).isEqualTo(1)
        assertThat(requestRepository.findByRequestId(requestId)?.status)
            .isEqualTo(StoryCreationRequestStatus.COMPLETED)
    }

    @Test
    fun `비소유 aged PENDING을 피해자 simpleCreationId로 재시도해도 바인딩 불일치로 409다`() {
        // Codex P1(3차) 바인딩 검증: 공격자가 자기 소유 aged PENDING 행(회수 대상)을 피해자 simpleCreationId로 재시도해도,
        // 세션의 creationRequestId가 공격자 requestId와 달라 재구성하지 않고 409. simpleCreationId를 신뢰하지 않는다.
        val victim = seedGeneratedStoryline()
        val victimBody = postSimpleStory(UUID.randomUUID(), victim.creationSession.id, victim.id, deviceA, null)
            .expectStatus().isCreated
            .expectBody().returnResult()

        // 공격자(deviceB)가 소유한 aged PENDING 행 — 자기 requestId, 어떤 세션도 만들지 않음.
        val attackerReq = UUID.randomUUID()
        requestRepository.saveAndFlush(
            StoryCreationRequest(
                requestId = attackerReq,
                deviceIdHash = deviceIdHasher.hash(deviceB),
                stage = StoryCreationStage.STORY_COMPLETION,
                status = StoryCreationRequestStatus.PENDING,
                updatedAt = Instant.now().minusSeconds(600),
            ),
        )

        // 공격자 requestId(회수)로 피해자 simpleCreationId를 찍어 재시도 → 바인딩 불일치 → 409, 미노출.
        val leak = postSimpleStory(attackerReq, victim.creationSession.id, victim.id, deviceB, null)
            .expectStatus().isEqualTo(409)
            .expectBody().returnResult()

        assertThat(String(leak.responseBody!!)).isNotEqualTo(String(victimBody.responseBody!!))
        assertThat(storyRepository.count()).isEqualTo(1)
    }

    @Test
    fun `완성된 게스트 세션에 새 requestId로 재요청하면 409이고 스토리를 누출하지 않는다`() {
        // Codex P1: 게스트(익명 소유) 세션은 완성 후에도 userId가 null이라 누구나 소유자로 통과한다. simpleCreationId는 순차 Long이라
        // 추측 가능하므로, 새 requestId(회수 아님)로 완성 스토리를 재구성해 돌려주면 안 된다(publicId·제목·시작설정 누출). 회수만 reconcile한다.
        val storyline = seedGeneratedStoryline()
        val created = postSimpleStory(UUID.randomUUID(), storyline.creationSession.id, storyline.id, deviceA, null)
            .expectStatus().isCreated
            .expectBody().jsonPath("$.id").isNotEmpty
            .returnResult()

        // 다른 디바이스(공격자)가 순차 simpleCreationId를 찍어 새 requestId로 재요청 → 409(회수 아님), 스토리 본문 미노출.
        val leak = postSimpleStory(UUID.randomUUID(), storyline.creationSession.id, storyline.id, deviceB, null)
            .expectStatus().isEqualTo(409)
            .expectBody().returnResult()

        // 409 응답 본문은 생성 응답(스토리 publicId·제목·시작설정)과 달라야 한다(누출 없음).
        assertThat(String(leak.responseBody!!)).isNotEqualTo(String(created.responseBody!!))
        // 두 번째 요청은 AI·저장을 타지 않는다(스토리 1개, compile 1회).
        assertThat(compileStoryCalls.get()).isEqualTo(1)
        assertThat(storyRepository.count()).isEqualTo(1)
    }

    @Test
    fun `완성된 게스트 세션을 새 requestId로 두 번 찔러도(FAILED 재시도) 스토리를 누출하지 않는다`() {
        // Codex P1(2차): 새 requestId 1차 프로브는 409→FAILED가 된다. 같은 requestId 재시도를 회수로 취급하면 reconcile이
        // 일어나 남의 완성 스토리를 열람할 수 있다. FAILED 재시도는 회수가 아니어야 한다(진짜 회수는 crash가 남긴 aged PENDING뿐).
        val storyline = seedGeneratedStoryline()
        val created = postSimpleStory(UUID.randomUUID(), storyline.creationSession.id, storyline.id, deviceA, null)
            .expectStatus().isCreated
            .expectBody().returnResult()

        val probeId = UUID.randomUUID()
        // 1차 프로브(공격자 디바이스, 새 requestId): 409 → 요청 행 FAILED 기록.
        postSimpleStory(probeId, storyline.creationSession.id, storyline.id, deviceB, null)
            .expectStatus().isEqualTo(409)
        // 2차(같은 requestId 재시도): FAILED 재실행이지만 회수가 아니므로 여전히 409, 스토리 미노출.
        val retry = postSimpleStory(probeId, storyline.creationSession.id, storyline.id, deviceB, null)
            .expectStatus().isEqualTo(409)
            .expectBody().returnResult()

        assertThat(String(retry.responseBody!!)).isNotEqualTo(String(created.responseBody!!))
        assertThat(storyRepository.count()).isEqualTo(1)
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
    @Autowired private lateinit var storyRepository: com.knk.manyak.story.repository.StoryRepository
}
