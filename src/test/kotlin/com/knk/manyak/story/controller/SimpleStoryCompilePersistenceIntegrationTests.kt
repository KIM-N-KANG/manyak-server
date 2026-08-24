package com.knk.manyak.story.controller

import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.image.service.CharacterImageStorage
import com.knk.manyak.story.client.AiCharacterAppearance
import com.knk.manyak.story.client.AiCharacterImage
import com.knk.manyak.story.client.AiResponseMeta
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStoryEnding
import com.knk.manyak.story.client.AiStoryMainEvent
import com.knk.manyak.story.client.AiStoryMeta
import com.knk.manyak.story.client.AiStorySettings
import com.knk.manyak.story.client.AiStoryStartSettings
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.support.DatabaseCleaner
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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

/**
 * KNK-520(B5-A): 간편 제작 컴파일 런타임 반영.
 *
 * - 백엔드가 스토리 장르로 로어북을 선별해 compile 요청 `lorebooks`에 싣고, 전달분을 `story_lorebooks`에 연결 저장한다.
 * - 컴파일 응답의 주요 사건·엔딩을 저작 경로와 같은 테이블(`story_main_events`·`story_endings`)에 저장한다.
 *
 * AI 클라이언트는 @Primary 페이크로 대체해 요청을 캡처하고 결정적 사건·엔딩을 반환한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimpleStoryCompilePersistenceIntegrationTests {

    companion object {
        @Volatile
        var capturedRequest: AiStoryCompileRequest? = null

        val mainEvents = listOf(
            AiStoryMainEvent("발단", "이야기가 시작된다", "주인공이 길을 나선다"),
            AiStoryMainEvent("전개", "갈등이 깊어진다", "적과 마주친다"),
            AiStoryMainEvent("절정", "위기가 최고조에 달한다", "최후의 선택을 한다"),
        )
        val endings = listOf(
            AiStoryEnding("해피", 5, "적을 물리치고 평화를 되찾는다", "따뜻한 에필로그"),
            AiStoryEnding("노말", 4, "일상으로 돌아간다", "잔잔한 에필로그"),
            AiStoryEnding("배드", 3, "돌이킬 수 없는 파국을 맞는다", "비극적 에필로그"),
        )

        // 특정 테스트가 엔딩·주요 사건 응답을 덮어쓸 수 있게 한다(이름 중복 케이스 등).
        @Volatile
        var endingsOverride: List<AiStoryEnding>? = null

        @Volatile
        var mainEventsOverride: List<AiStoryMainEvent>? = null

        // 유효한 base64면 충분하다(디코딩만 하고 내용은 보지 않는다).
        const val WEBP_BASE64 = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA=="
        const val WEBP_BYTE_LENGTH = 34
        const val FAIL_ALL_UPLOADS = "FAIL_ALL"

        @Volatile
        var characterAppearances: List<AiCharacterAppearance> = emptyList()

        @Volatile
        var characterImages: List<AiCharacterImage> = emptyList()

        // 스텁 스토리지가 받은 (objectKey, contentType, 바이트 길이)를 기록한다. 업로드 호출 여부·키 규칙 검증용.
        val uploads = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Int>>()

        // 보상 삭제로 지운 객체 키. 저장 실패 시 고아 객체 정리 검증용.
        val deletes = java.util.concurrent.CopyOnWriteArrayList<String>()

        // 업로드를 시도한 객체 키(실패분 포함). 실패한 업로드의 키를 지우는지 검증하는 데 쓴다.
        val attemptedUploadKeys = java.util.concurrent.CopyOnWriteArrayList<String>()

        // 값이 있으면 그 인물 이름의 업로드에서 예외를 던진다(S3 장애 재현).
        @Volatile
        var uploadFailureKeyMarker: String? = null

        // true면 업로드 시점에 그 객체 키가 가리키는 storyPublicId로 스토리 행을 **커밋**한다.
        // 커밋은 반영됐는데 응답만 유실돼 예외가 난 모호한 실패와 같은 상태를 만든다.
        @Volatile
        var commitStoryOnUpload: Boolean = false

        // 세션 경합 재현용: 값이 있으면 compile 호출 도중 그 세션을 STORY_CREATED로 **커밋**한다.
        // 다른 requestId를 가진 동시 요청이 먼저 저장을 끝낸 상황과 같아, compile 후 잠금 시점에 409가 난다.
        @Volatile
        var flipSessionToCreatedId: Long? = null
    }

    @TestConfiguration
    class FakeAiClientConfig {
        @Bean
        @Primary
        fun fakeStoryAiClient(
            sessionRepository: StoryCreationSessionRepository,
            transactionManager: PlatformTransactionManager,
        ): StoryAiClient = object : StoryAiClient {
            override fun createStorylines(request: AiStorylinesRequest, traceLink: AiTraceLink): AiStorylinesResponse =
                AiStorylinesResponse(stories = emptyList(), meta = AiResponseMeta())

            override fun compileStory(request: AiStoryCompileRequest, traceLink: AiTraceLink): AiStoryCompileResponse {
                capturedRequest = request
                flipSessionToCreatedId?.let { sessionId ->
                    // 별도 트랜잭션으로 커밋해야 compileAndPersist의 findByIdForUpdate가 새 상태를 본다.
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        val session = sessionRepository.findById(sessionId).orElseThrow()
                        session.status = StoryCreationSessionStatus.STORY_CREATED
                        sessionRepository.saveAndFlush(session)
                    }
                }
                return AiStoryCompileResponse(
                    stories = AiStoryMeta("생성된 스토리", "한 줄 소개", "설명"),
                    storySettings = AiStorySettings("세계관", "캐릭터", "역할", "규칙"),
                    storyStartSettings = AiStoryStartSettings("시작", "상황", "프롤로그"),
                    storySuggestedInputs = listOf("추천1", "추천2", "추천3"),
                    storyMainEvents = mainEventsOverride ?: mainEvents,
                    storyEndings = endingsOverride ?: endings,
                    characterAppearances = characterAppearances,
                    characterImages = characterImages,
                    meta = AiResponseMeta(),
                )
            }
        }

        /** 실제 S3 대신 업로드를 기록하는 스텁. 반환 URL은 운영과 같은 `base-url + 객체 키` 모양이다. */
        @Bean
        @Primary
        fun fakeCharacterImageStorage(
            storyRepository: StoryRepository,
            transactionManager: PlatformTransactionManager,
        ): CharacterImageStorage = object : CharacterImageStorage {
            override fun upload(objectKey: String, bytes: ByteArray, contentType: String): String {
                attemptedUploadKeys += objectKey
                if (commitStoryOnUpload) {
                    // 객체 키(characters/generated/{storyPublicId}/{uuid}.webp)에서 스토리 public_id를 꺼내
                    // 별도 트랜잭션으로 스토리를 커밋한다. 이후 저장 트랜잭션이 깨져도 스토리 행은 남는다.
                    val storyPublicId = java.util.UUID.fromString(objectKey.split("/")[2])
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        storyRepository.saveAndFlush(Story(publicId = storyPublicId, title = "커밋된 스토리"))
                    }
                }
                uploadFailureKeyMarker?.let { marker ->
                    if (marker == FAIL_ALL_UPLOADS) {
                        throw IllegalStateException("S3 업로드 실패(테스트)")
                    }
                }
                uploads += Triple(objectKey, contentType, bytes.size)
                return "https://cdn.test/$objectKey"
            }

            override fun delete(objectKey: String) {
                deletes += objectKey
            }
        }
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var tagRepository: StoryCreationTagRepository
    @Autowired private lateinit var sessionRepository: StoryCreationSessionRepository
    @Autowired private lateinit var sessionTagRepository: StoryCreationSessionTagRepository
    @Autowired private lateinit var storylineRepository: StoryCreationStorylineRepository
    @Autowired private lateinit var lorebookRepository: LorebookRepository
    @Autowired private lateinit var storyLorebookRepository: StoryLorebookRepository
    @Autowired private lateinit var storyMainEventRepository: StoryMainEventRepository
    @Autowired private lateinit var storyEndingRepository: StoryEndingRepository
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyStartSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var storyCharacterRepository: StoryCharacterRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    // 레지스트리가 여럿이면(prometheus·otlp 동시 활성) CompositeMeterRegistry가 @Primary라 인터페이스로 받는다.
    @Autowired private lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun setUp() {
        capturedRequest = null
        endingsOverride = null
        mainEventsOverride = null
        flipSessionToCreatedId = null
        characterAppearances = emptyList()
        characterImages = emptyList()
        uploads.clear()
        deletes.clear()
        attemptedUploadKeys.clear()
        commitStoryOnUpload = false
        uploadFailureKeyMarker = null
        databaseCleaner.cleanAll()
    }

    @Test
    fun `컴파일은 스토리 장르로 로어북을 선별해 요청에 싣고 story_lorebooks에 연결 저장한다`() {
        // 활성 로맨스 2개(선별 대상), 다른 장르 1개·비활성 1개(제외 대상).
        val loreA = lorebookRepository.save(Lorebook(name = "로맨스 용어 A", genre = "로맨스", content = "A 본문", sortOrder = 1))
        val loreB = lorebookRepository.save(Lorebook(name = "로맨스 용어 B", genre = "로맨스", content = "B 본문", sortOrder = 2))
        lorebookRepository.save(Lorebook(name = "스릴러 용어", genre = "스릴러", content = "무관", sortOrder = 1))
        lorebookRepository.save(Lorebook(name = "비활성 로맨스", genre = "로맨스", content = "무관", sortOrder = 3, isActive = false))
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        // 요청에 활성 로맨스 로어북만 순서대로 실렸다.
        val requestLorebooks = capturedRequest!!.lorebooks
        assertThat(requestLorebooks.map { it.name }).containsExactly("로맨스 용어 A", "로맨스 용어 B")
        assertThat(requestLorebooks.map { it.content }).containsExactly("A 본문", "B 본문")

        // story_lorebooks에 전달분이 1-based 순서로 연결 저장됐다(ck_story_lorebooks_sort_order > 0).
        val storyId = storyRepository.findAll().first().id
        val links = storyLorebookRepository.findByStoryIdOrderBySortOrderAscIdAsc(storyId)
        assertThat(links.map { it.lorebook.id }).containsExactly(loreA.id, loreB.id)
        assertThat(links.map { it.sortOrder.toInt() }).containsExactly(1, 2)
    }

    @Test
    fun `컴파일 응답의 주요 사건과 엔딩이 저작 테이블에 저장된다`() {
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        // 주요 사건은 스토리 소유, sort_order 0-based(일반 제작과 동일).
        val savedEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
        assertThat(savedEvents.map { it.name }).containsExactly("발단", "전개", "절정")
        assertThat(savedEvents.map { it.keySentence }).containsExactly("주인공이 길을 나선다", "적과 마주친다", "최후의 선택을 한다")
        assertThat(savedEvents.map { it.sortOrder.toInt() }).containsExactly(0, 1, 2)

        // 엔딩은 시작 설정 스코프, sort_order 1-based(ck_story_endings_order > 0).
        val startSetting = storyStartSettingRepository.findFirstByStoryIdOrderByIdAsc(story.id)!!
        val savedEndings = storyEndingRepository.findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(startSetting.id)
        assertThat(savedEndings.map { it.name }).containsExactly("해피", "노말", "배드")
        assertThat(savedEndings.map { it.minTurns }).containsExactly(5, 4, 3)
        assertThat(savedEndings.map { it.achievementCondition }).containsExactly(
            "적을 물리치고 평화를 되찾는다",
            "일상으로 돌아간다",
            "돌이킬 수 없는 파국을 맞는다",
        )
        assertThat(savedEndings.map { it.sortOrder.toInt() }).containsExactly(1, 2, 3)
    }

    @Test
    fun `컴파일 응답의 엔딩 이름이 중복되면 502이고 스토리가 저장되지 않는다`() {
        // 이름 기반 도달 매칭의 모호성을 막기 위해, 시작 설정 내 이름 중복 컴파일 응답은 불완전 AI 응답(502)으로 거부한다.
        endingsOverride = listOf(
            AiStoryEnding("같은엔딩", 5, "조건 A", "에필로그 A"),
            AiStoryEnding("같은엔딩", 4, "조건 B", "에필로그 B"),
            AiStoryEnding("다른엔딩", 3, "조건 C", "에필로그 C"),
        )
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isEqualTo(502)

        assertThat(storyRepository.findAll()).isEmpty()
    }

    @Test
    fun `컴파일 응답의 주요 사건 이름이 중복되면 502이고 스토리가 저장되지 않는다`() {
        mainEventsOverride = listOf(
            AiStoryMainEvent("같은사건", "설명 A", "키 문장 A"),
            AiStoryMainEvent("같은사건", "설명 B", "키 문장 B"),
            AiStoryMainEvent("다른사건", "설명 C", "키 문장 C"),
        )
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isEqualTo(502)

        assertThat(storyRepository.findAll()).isEmpty()
    }

    @Test
    fun `스토리 완성이 성공하면 story_creation_duration 타이머의 success를 올린다`() {
        val storyline = persistStorylineWithGenre("로맨스")
        // @SpringBootTest 컨텍스트는 클래스 간 캐시 공유라 레지스트리 count가 누적된다. 절대값이 아니라 증가분을 본다.
        val before = storyCreationTimerCount("success")

        postSimpleStory(storyline).expectStatus().isCreated

        assertThat(storyCreationTimerCount("success")).isEqualTo(before + 1)
    }

    @Test
    fun `AI 호출 실패(502)는 failure를 올리고 rejected는 올리지 않는다`() {
        endingsOverride = listOf(
            AiStoryEnding("같은엔딩", 5, "조건 A", "에필로그 A"),
            AiStoryEnding("같은엔딩", 4, "조건 B", "에필로그 B"),
            AiStoryEnding("다른엔딩", 3, "조건 C", "에필로그 C"),
        )
        val storyline = persistStorylineWithGenre("로맨스")
        val beforeFailure = storyCreationTimerCount("failure")
        val beforeRejected = storyCreationTimerCount("rejected")

        postSimpleStory(storyline).expectStatus().isEqualTo(502)

        // 실제 생성을 시도하다 AI에서 깨진 것이라 failure다(우리가 지연·실패율로 보려는 바로 그 구간).
        assertThat(storyCreationTimerCount("failure")).isEqualTo(beforeFailure + 1)
        assertThat(storyCreationTimerCount("rejected")).isEqualTo(beforeRejected)
    }

    @Test
    fun `compile을 마친 뒤 세션 경합으로 나는 409는 failure를 올리고 rejected는 올리지 않는다`() {
        // 같은 세션에 requestId가 다른 완성 요청 둘이 겹치면, 둘 다 잠금 없는 초기 상태 검사를 통과해 compile을 호출한다.
        // 진 쪽은 compileAndPersist의 findByIdForUpdate로 잠금을 잡은 뒤 STORY_CREATED를 보고 409를 던진다.
        // HTTP 상태는 4xx지만 AI 호출을 이미 마친 뒤라 수 초~180초가 걸린 **진짜 생성 실패**다(밀리초 거부가 아니다).
        // 페이크 AI가 compile 도중 세션을 STORY_CREATED로 커밋해 이 경합을 결정적으로 재현한다.
        val storyline = persistStorylineWithGenre("로맨스")
        flipSessionToCreatedId = storyline.creationSession.id
        val beforeFailure = storyCreationTimerCount("failure")
        val beforeRejected = storyCreationTimerCount("rejected")

        postSimpleStory(storyline).expectStatus().isEqualTo(409)

        assertThat(storyCreationTimerCount("failure")).isEqualTo(beforeFailure + 1)
        assertThat(storyCreationTimerCount("rejected")).isEqualTo(beforeRejected)
    }

    @Test
    fun `생성 시도 이전 4xx 거부는 rejected를 올리고 failure는 올리지 않는다`() {
        // 존재하지 않는 simpleCreationId → 404. AI 호출 없이 DB 조회 몇 번으로 끝나는 밀리초 경로라,
        // failure에 섞이면 실패 p95를 끌어내려 AI가 느려져도 지표가 개선된 것처럼 보인다.
        val beforeRejected = storyCreationTimerCount("rejected")
        val beforeFailure = storyCreationTimerCount("failure")

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":999999999,"storylineId":1,"additionalInfos":[]}""")
            .exchange()
            .expectStatus().isNotFound

        assertThat(storyCreationTimerCount("rejected")).isEqualTo(beforeRejected + 1)
        assertThat(storyCreationTimerCount("failure")).isEqualTo(beforeFailure)
    }

    @Test
    fun `컴파일 응답의 인물 외형과 이미지가 story_characters에 저장된다`() {
        characterAppearances = listOf(
            AiCharacterAppearance("서준", "MALE", "20대 초반", "마른 체형", "선한 눈매", "검은 단발", "교복", "왼쪽 눈 밑 점"),
            AiCharacterAppearance("하나", "FEMALE", "20대 초반", "보통 체형", "둥근 얼굴", "갈색 장발", "코트", "빨간 목도리"),
        )
        characterImages = listOf(
            AiCharacterImage("서준", imageBase64 = WEBP_BASE64, contentType = "image/webp"),
            AiCharacterImage("하나", imageBase64 = WEBP_BASE64, contentType = "image/webp"),
        )
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        val saved = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
        assertThat(saved.map { it.name }).containsExactly("서준", "하나")
        // 외형 7필드가 그대로 저장된다(통글에 실리지 않는 별도 데이터).
        val seojun = saved.first()
        assertThat(seojun.gender).isEqualTo("MALE")
        assertThat(seojun.age).isEqualTo("20대 초반")
        assertThat(seojun.body).isEqualTo("마른 체형")
        assertThat(seojun.face).isEqualTo("선한 눈매")
        assertThat(seojun.hair).isEqualTo("검은 단발")
        assertThat(seojun.outfit).isEqualTo("교복")
        assertThat(seojun.visualIdentity).isEqualTo("왼쪽 눈 밑 점")
        // 업로드된 URL이 인물에 붙는다.
        assertThat(saved.map { it.imageUrl }).allSatisfy { url -> assertThat(url).startsWith("https://cdn.test/") }

        // 객체 키는 characters/generated/{storyPublicId}/{uuid}.webp이고 Content-Type은 응답 값이다.
        assertThat(uploads).hasSize(2)
        assertThat(uploads.map { it.first })
            .allSatisfy { key -> assertThat(key).matches("characters/generated/${story.publicId}/[0-9a-f-]{36}\\.webp") }
        assertThat(uploads.map { it.second }).containsOnly("image/webp")
        assertThat(uploads.map { it.third }).containsOnly(WEBP_BYTE_LENGTH)
    }

    @Test
    fun `이미지 생성에 실패한 인물은 image_url 없이 저장되고 스토리 생성은 계속된다`() {
        // 외형은 인물 전원, 이미지는 외형이 있는 인물만 → 두 배열의 길이가 다르고 name으로 매칭한다.
        characterAppearances = listOf(
            AiCharacterAppearance("서준", "MALE", "20대 초반"),
            AiCharacterAppearance("하나", "FEMALE", "20대 초반"),
            AiCharacterAppearance("외형없음"),
        )
        characterImages = listOf(
            AiCharacterImage("서준", imageBase64 = WEBP_BASE64, contentType = "image/webp"),
            AiCharacterImage("하나", error = "rate_limited"),
        )
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        val saved = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
        // 이미지 유무와 무관하게 인물 전원이 저장된다.
        assertThat(saved.map { it.name }).containsExactly("서준", "하나", "외형없음")
        assertThat(saved.single { it.name == "서준" }.imageUrl).startsWith("https://cdn.test/")
        assertThat(saved.single { it.name == "하나" }.imageUrl).isNull()
        assertThat(saved.single { it.name == "외형없음" }.imageUrl).isNull()
        assertThat(uploads).hasSize(1)
    }

    @Test
    fun `인물 배열이 비어 있으면 story_characters를 만들지 않고 스토리만 저장한다`() {
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        assertThat(storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)).isEmpty()
        assertThat(uploads).isEmpty()
    }

    @Test
    fun `S3 업로드가 실패해도 인물은 image_url 없이 저장되고 실패한 객체 키를 지운다`() {
        characterAppearances = listOf(AiCharacterAppearance("서준", "MALE", "20대 초반"))
        characterImages = listOf(AiCharacterImage("서준", imageBase64 = WEBP_BASE64, contentType = "image/webp"))
        uploadFailureKeyMarker = FAIL_ALL_UPLOADS
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        val saved = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
        assertThat(saved.map { it.name }).containsExactly("서준")
        assertThat(saved.single().imageUrl).isNull()
        // 업로드가 예외로 끝나도 저장소는 객체를 받았을 수 있다. 그 키는 반환값에 실리지 않아 트랜잭션 보상 삭제가
        // 닿지 못하므로, 업로드 실패 지점에서 바로 지워야 고아 객체가 남지 않는다(Codex P2).
        assertThat(deletes).containsExactly(attemptedUploadKeys.single())
    }

    @Test
    fun `이름이 중복된 인물은 첫 항목만 남고 중복분은 업로드하지 않는다`() {
        characterAppearances = listOf(
            AiCharacterAppearance("서준", "MALE", hair = "검은 단발"),
            AiCharacterAppearance("서준", "FEMALE", hair = "금발"),
        )
        characterImages = listOf(
            AiCharacterImage("서준", imageBase64 = WEBP_BASE64, contentType = "image/webp"),
            AiCharacterImage("서준", imageBase64 = WEBP_BASE64, contentType = "image/webp"),
        )
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        val saved = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
        assertThat(saved).hasSize(1)
        assertThat(saved.single().hair).isEqualTo("검은 단발")
        // 버려질 중복분은 업로드 자체를 하지 않는다(고아 객체 방지).
        assertThat(uploads).hasSize(1)
        assertThat(saved.single().imageUrl).isEqualTo("https://cdn.test/${uploads.single().first}")
    }

    @Test
    fun `앞 100자가 같은 긴 이름은 하나로 합쳐지고 첫 항목이 남는다`() {
        // 이름 컬럼이 VARCHAR(100)이라 절단 후 충돌한다. 이때도 중복과 같은 규칙(첫 항목 유지)을 따른다.
        val prefix = "가".repeat(100)
        characterAppearances = listOf(
            AiCharacterAppearance(prefix + "첫번째", hair = "검은 단발"),
            AiCharacterAppearance(prefix + "두번째", hair = "금발"),
        )
        characterImages = listOf(AiCharacterImage(prefix + "두번째", imageBase64 = WEBP_BASE64, contentType = "image/webp"))
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        val saved = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
        assertThat(saved).hasSize(1)
        assertThat(saved.single().name).isEqualTo(prefix)
        assertThat(saved.single().hair).isEqualTo("검은 단발")
        // 절단 후 같은 이름이라 이미지는 그 인물에 붙는다.
        assertThat(saved.single().imageUrl).isNotNull()
    }

    @Test
    fun `계약 상한을 넘는 인물은 앞 5명만 저장하고 초과분은 업로드하지 않는다`() {
        // 스펙 §5-3-3의 인물 상한은 0~5명이다. 초과는 AI 응답의 결함이지만 502로 올리지 않고 버린다(graceful).
        characterAppearances = (1..7).map { AiCharacterAppearance("인물$it", "MALE") }
        characterImages = (1..7).map {
            AiCharacterImage("인물$it", imageBase64 = WEBP_BASE64, contentType = "image/webp")
        }
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        val saved = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
        assertThat(saved.map { it.name }).containsExactly("인물1", "인물2", "인물3", "인물4", "인물5")
        // 버려질 인물의 이미지는 올리지도 않는다(고아 객체 방지).
        assertThat(uploads).hasSize(5)
    }

    @Test
    fun `외형과 이미지의 이름이 갈려도 합쳐서 5명까지만 저장한다`() {
        // 상한이 배열별이면 외형 3명 + 이미지 4명(이름 1개만 겹침)이 6행이 된다. 상한은 최종 인물 집합에 걸린다.
        // 합집합 순서는 외형이 앞이고(first-wins) 이름이 겹치지 않는 이미지가 뒤를 잇는다 → A B C / D E F 중 F가 잘린다.
        characterAppearances = listOf("A", "B", "C").map { AiCharacterAppearance(it, "MALE") }
        characterImages = listOf("C", "D", "E", "F").map {
            AiCharacterImage(it, imageBase64 = WEBP_BASE64, contentType = "image/webp")
        }
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        val saved = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
        assertThat(saved.map { it.name }).containsExactly("A", "B", "C", "D", "E")
        // 잘린 F는 업로드도 하지 않는다. 남은 C·D·E만 올라가고, 외형만 있는 A·B는 이미지가 없다.
        assertThat(uploads).hasSize(3)
        assertThat(saved.filter { it.imageUrl != null }.map { it.name }).containsExactly("C", "D", "E")
    }

    @Test
    fun `이름이 비어 있는 인물은 저장하지 않고 업로드도 하지 않는다`() {
        characterAppearances = listOf(AiCharacterAppearance("   "))
        characterImages = listOf(AiCharacterImage("", imageBase64 = WEBP_BASE64, contentType = "image/webp"))
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        assertThat(storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)).isEmpty()
        assertThat(uploads).isEmpty()
    }

    @Test
    fun `base64가 깨진 인물은 이미지 없이 저장되고 스토리 생성은 성공한다`() {
        characterAppearances = listOf(AiCharacterAppearance("서준", "MALE"))
        characterImages = listOf(AiCharacterImage("서준", imageBase64 = "!!!not-base64!!!", contentType = "image/webp"))
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        assertThat(storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id).single().imageUrl).isNull()
        assertThat(uploads).isEmpty()
        // 디코딩이 업로드보다 먼저라 올라간 객체가 없다 — 지울 것도 없다.
        assertThat(deletes).isEmpty()
    }

    @Test
    fun `error가 실린 인물은 base64가 함께 와도 업로드하지 않는다`() {
        // 에러 코드가 우선이다 — 실패로 표시된 이미지를 올려 두면 쓰이지 않는 객체만 남는다.
        characterAppearances = listOf(AiCharacterAppearance("서준", "MALE"))
        characterImages = listOf(
            AiCharacterImage("서준", imageBase64 = WEBP_BASE64, contentType = "image/webp", error = "rejected"),
        )
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isCreated

        val story = storyRepository.findAll().first()
        assertThat(storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id).single().imageUrl).isNull()
        assertThat(uploads).isEmpty()
    }

    @Test
    fun `저장 트랜잭션이 실패하면 이미 올린 인물 이미지를 지운다`() {
        // 엔딩 이름 중복으로 502를 내 저장을 롤백시킨다. 업로드는 트랜잭션보다 먼저 끝나 있으므로
        // 정리하지 않으면 어디에도 기록되지 않은 고아 객체가 남는다(키가 매번 UUID라 재시도로도 회수 불가).
        endingsOverride = listOf(
            AiStoryEnding("같은엔딩", 5, "조건 A", "에필로그 A"),
            AiStoryEnding("같은엔딩", 4, "조건 B", "에필로그 B"),
        )
        characterAppearances = listOf(AiCharacterAppearance("서준", "MALE"))
        characterImages = listOf(AiCharacterImage("서준", imageBase64 = WEBP_BASE64, contentType = "image/webp"))
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isEqualTo(502)

        assertThat(storyRepository.findAll()).isEmpty()
        assertThat(uploads).hasSize(1)
        assertThat(deletes).containsExactly(uploads.single().first)
    }

    @Test
    fun `저장이 예외로 끝나도 스토리 행이 남아 있으면 인물 이미지를 지우지 않는다`() {
        // 커밋이 서버에 반영됐는데 응답만 유실돼 예외가 나는 모호한 실패를 재현한다. 그 상태에서는 스토리와
        // image_url이 영구히 남으므로 객체를 지우면 DB가 가리키는 이미지가 깨진다.
        // 재현 방법: 스텁 스토리지가 업로드 시점에 그 storyPublicId로 스토리를 **커밋**한다. 이어지는 저장
        // 트랜잭션은 public_id 유니크 위반으로 깨지므로(500), 보상 삭제 판정이 보는 상태는 실제 모호한 실패와
        // 같다 — 저장 트랜잭션은 예외로 끝났는데 그 public_id의 스토리 행은 존재한다.
        characterAppearances = listOf(AiCharacterAppearance("서준", "MALE"))
        characterImages = listOf(AiCharacterImage("서준", imageBase64 = WEBP_BASE64, contentType = "image/webp"))
        commitStoryOnUpload = true
        val storyline = persistStorylineWithGenre("로맨스")

        postSimpleStory(storyline).expectStatus().isEqualTo(500)

        assertThat(uploads).hasSize(1)
        // 스토리 행이 남아 있으니 지우지 않는다(고아 객체 비용 < 깨진 이미지 피해).
        assertThat(deletes).isEmpty()
        assertThat(storyRepository.findAll()).hasSize(1)
    }

    private fun storyCreationTimerCount(outcome: String): Long =
        meterRegistry.find("manyak.story.creation.duration").tag("outcome", outcome).timer()?.count() ?: 0L

    private fun persistStorylineWithGenre(genre: String): StoryCreationStoryline {
        val session = sessionRepository.save(
            StoryCreationSession(userId = null, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val genreTag = tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = genre,
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = 0,
                isActive = true,
            ),
        )
        sessionTagRepository.save(StoryCreationSessionTag(creationSession = session, tag = genreTag))
        return storylineRepository.save(
            StoryCreationStoryline(
                creationSession = session,
                storylineText = "예시 스토리라인",
                storylineOrder = 1,
            ),
        )
    }

    private fun postSimpleStory(storyline: StoryCreationStoryline): RestTestClient.ResponseSpec =
        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
}
