package com.knk.manyak.story.controller

import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStoryItem
import com.knk.manyak.story.client.AiStoryMeta
import com.knk.manyak.story.client.AiStorySettings
import com.knk.manyak.story.client.AiStoryStartSettings
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationExample
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationExampleRecommendedInfoRepository
import com.knk.manyak.story.repository.StoryCreationExampleRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.transaction.support.TransactionSynchronizationManager

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var tagRepository: StoryCreationTagRepository

    @Autowired
    private lateinit var sessionRepository: StoryCreationSessionRepository

    @Autowired
    private lateinit var sessionTagRepository: StoryCreationSessionTagRepository

    @Autowired
    private lateinit var exampleRepository: StoryCreationExampleRepository

    @Autowired
    private lateinit var recommendedInfoRepository: StoryCreationExampleRecommendedInfoRepository

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storyAiClient: CapturingStoryAiClient

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        storyAiClient.reset()
    }

    @Test
    fun `간편 제작 태그 목록을 조회한다`() {
        seedTag(SimpleStoryTagCategory.SUPPORTING_CHARACTER, "비밀스러운 조력자", 10)
        seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        seedTag(SimpleStoryTagCategory.PROTAGONIST, "기억상실", 10)
        tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "사용자 입력",
                tagSource = StoryCreationTagSource.CUSTOM,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/simple/tags")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)
            .jsonPath("$[0].name").isEqualTo("판타지")
            .jsonPath("$[0].category").isEqualTo("GENRE")
            .jsonPath("$[1].name").isEqualTo("기억상실")
            .jsonPath("$[1].category").isEqualTo("PROTAGONIST")
            .jsonPath("$[2].name").isEqualTo("비밀스러운 조력자")
            .jsonPath("$[2].category").isEqualTo("SUPPORTING_CHARACTER")
    }

    @Test
    fun `로컬 프론트 개발 서버의 CORS preflight를 허용한다`() {
        listOf(
            "http://localhost:3000",
            "http://192.168.0.12:3000",
        ).forEach { origin ->
            restTestClient.options()
                .uri("/api/v1/stories/simple/tags")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
        }
    }

    @Test
    fun `선택 태그로 스토리라인을 생성한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        val protagonist = seedTag(SimpleStoryTagCategory.PROTAGONIST, "기억상실", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "selectedTagIds": [${genre.id}, ${protagonist.id}]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.simpleCreationId").isNumber
            .jsonPath("$.selectedTags.length()").isEqualTo(2)
            .jsonPath("$.selectedTags[0].name").isEqualTo("판타지")
            .jsonPath("$.storylines.length()").isEqualTo(3)
            .jsonPath("$.storylines[0].story").isEqualTo("생성 스토리 1")
            .jsonPath("$.storylines[0].recommendedInfos.length()").isEqualTo(3)
            .jsonPath("$.storylines[0].recommendedInfos[0].text").isEqualTo("추가 정보 1-1")

        val aiRequest = storyAiClient.lastRequest
        requireNotNull(aiRequest)
        check(storyAiClient.transactionActiveDuringCall == false)
        check(aiRequest.genreTags == listOf("판타지"))
        check(aiRequest.protagonistTags == listOf("기억상실"))
        check(aiRequest.supportingTags.isEmpty())
        check(exampleRepository.count() == 3L)
        check(recommendedInfoRepository.count() == 9L)
    }

    @Test
    fun `AI가 추천 추가 정보를 비워 응답해도 빈 목록으로 스토리라인을 생성한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        storyAiClient.emptyRecommendedInfos = true

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "selectedTagIds": [${genre.id}]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.storylines.length()").isEqualTo(3)
            .jsonPath("$.storylines[0].recommendedInfos.length()").isEqualTo(0)

        check(recommendedInfoRepository.count() == 0L)
    }

    @Test
    fun `직접 추가 태그만으로 스토리라인을 생성한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "customTags": [
                    {
                      "name": "비밀스러운 조력자",
                      "category": "SUPPORTING_CHARACTER"
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.length()").isEqualTo(1)
            .jsonPath("$.selectedTags[0].name").isEqualTo("비밀스러운 조력자")
            .jsonPath("$.selectedTags[0].category").isEqualTo("SUPPORTING_CHARACTER")

        val aiRequest = storyAiClient.lastRequest
        requireNotNull(aiRequest)
        check(aiRequest.supportingTags == listOf("비밀스러운 조력자"))
    }

    @Test
    fun `이미 저장된 직접 추가 태그는 재사용한다`() {
        tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "마법 학교",
                tagSource = StoryCreationTagSource.CUSTOM,
            ),
        )

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "customTags": [
                    {
                      "name": "마법 학교",
                      "category": "GENRE"
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.length()").isEqualTo(1)
            .jsonPath("$.selectedTags[0].name").isEqualTo("마법 학교")

        check(tagRepository.count() == 1L)
    }

    @Test
    fun `같은 출처와 분류와 이름의 태그는 중복 저장할 수 없다`() {
        tagRepository.saveAndFlush(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "마법 학교",
                tagSource = StoryCreationTagSource.CUSTOM,
            ),
        )

        assertThrows<DataIntegrityViolationException> {
            tagRepository.saveAndFlush(
                StoryCreationTag(
                    category = SimpleStoryTagCategory.GENRE,
                    name = "마법 학교",
                    tagSource = StoryCreationTagSource.CUSTOM,
                ),
            )
        }
    }

    @Test
    fun `간편 제작 진행 정보가 수정되면 updatedAt이 갱신된다`() {
        val session = sessionRepository.saveAndFlush(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val beforeUpdatedAt = session.updatedAt

        Thread.sleep(5)
        session.status = StoryCreationSessionStatus.STORY_CREATED
        val updatedSession = sessionRepository.saveAndFlush(session)

        check(updatedSession.updatedAt.isAfter(beforeUpdatedAt))
    }

    @Test
    fun `태그가 없으면 스토리라인 생성 요청을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"selectedTagIds":[],"customTags":[]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.code").isEqualTo("BAD_REQUEST")
            .jsonPath("$.message").isEqualTo("요청 값이 올바르지 않습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
            .jsonPath("$.details.length()").isNumber
    }

    @Test
    fun `존재하지 않는 선택 태그가 있으면 스토리라인 생성 요청을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"selectedTagIds":[999999]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.code").isEqualTo("BAD_REQUEST")
            .jsonPath("$.message").isEqualTo("사용할 수 없는 태그 ID가 포함되어 있습니다: 999999")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
    }

    @Test
    fun `AI 서버 오류는 Bad Gateway로 응답한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        storyAiClient.fail = true

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"selectedTagIds":[${genre.id}]}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo(502)
            .jsonPath("$.code").isEqualTo("BAD_GATEWAY")
            .jsonPath("$.message").isEqualTo("AI 스토리라인 생성 요청에 실패했습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
    }

    @Test
    fun `스토리라인 생성 API는 Bad Gateway 응답을 문서화한다`() {
        restTestClient.get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paths['/api/v1/stories/simple/storylines'].post.responses['502'].description")
            .isEqualTo("AI 서버 요청 실패")
    }

    @Test
    fun `선택한 스토리라인으로 최종 스토리를 생성하고 저장한다`() {
        val seeded = seedGeneratedSession()

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "simpleCreationId": ${seeded.sessionId},
                  "storylineId": ${seeded.exampleIds[1]},
                  "additionalInfos": ["주인공은 신중하다", "결말은 여운 있게"]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            // 생성된 스토리 id는 순차 PK가 아니라 공개 식별자(public_id UUID 문자열)다.
            .jsonPath("$.id").isNotEmpty
            .jsonPath("$.storyId").doesNotExist()
            .jsonPath("$.title").isEqualTo("잿빛 왕관")
            .jsonPath("$.genres.length()").isEqualTo(2)
            .jsonPath("$.genres[0]").isEqualTo("다크 판타지")
            .jsonPath("$.genres[1]").isEqualTo("정치극")
            .jsonPath("$.startSetting.name").isEqualTo("선왕의 장례식 날")
            .jsonPath("$.settings").doesNotExist()
            .jsonPath("$.suggestedInputs").doesNotExist()

        val compileRequest = storyAiClient.lastCompileRequest
        requireNotNull(compileRequest)
        check(storyAiClient.compileTransactionActive == false)
        check(compileRequest.genreTags == listOf("다크 판타지", "정치극"))
        check(compileRequest.protagonistTags == listOf("신중한"))
        check(compileRequest.selectedStoryline == "스토리라인 2")
        check(compileRequest.extraInfo == "주인공은 신중하다\n결말은 여운 있게")

        check(storyRepository.count() == 1L)
        check(storySettingRepository.count() == 1L)
        check(storyStartSettingRepository.count() == 1L)
        check(storySuggestedInputRepository.count() == 3L)

        val session = sessionRepository.findById(seeded.sessionId).orElseThrow()
        check(session.status == StoryCreationSessionStatus.STORY_CREATED)
        check(session.storyId != null)
        val selected = exampleRepository.findById(seeded.exampleIds[1]).orElseThrow()
        check(selected.isSelected)
    }

    @Test
    fun `존재하지 않는 진행 정보면 스토리 생성을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"simpleCreationId":999999,"storylineId":1}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("간편 제작 진행 정보를 찾을 수 없습니다.")
    }

    @Test
    fun `세션에 속하지 않은 스토리라인이면 스토리 생성을 거절한다`() {
        val seeded = seedGeneratedSession()

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"simpleCreationId":${seeded.sessionId},"storylineId":999999}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("선택한 스토리라인을 찾을 수 없습니다.")
    }

    @Test
    fun `이미 스토리가 생성된 진행이면 충돌로 응답한다`() {
        val seeded = seedGeneratedSession()
        val session = sessionRepository.findById(seeded.sessionId).orElseThrow()
        session.status = StoryCreationSessionStatus.STORY_CREATED
        sessionRepository.saveAndFlush(session)

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"simpleCreationId":${seeded.sessionId},"storylineId":${seeded.exampleIds[0]}}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.status").isEqualTo(409)
            .jsonPath("$.message").isEqualTo("이미 스토리가 생성된 간편 제작 진행입니다.")
    }

    @Test
    fun `AI 응답 제목과 한 줄 소개가 컬럼 길이를 초과하면 잘라서 저장한다`() {
        val seeded = seedGeneratedSession()
        storyAiClient.compileTitle = "가".repeat(150)
        storyAiClient.compileOneLineIntro = "나".repeat(300)

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"simpleCreationId":${seeded.sessionId},"storylineId":${seeded.exampleIds[0]}}""")
            .exchange()
            .expectStatus().isCreated

        val story = storyRepository.findAll().single()
        check(story.title.length == 100)
        check(story.oneLineIntro?.length == 255)
    }

    @Test
    fun `최종 스토리 생성 AI 오류는 Bad Gateway로 응답한다`() {
        val seeded = seedGeneratedSession()
        storyAiClient.compileFail = true

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"simpleCreationId":${seeded.sessionId},"storylineId":${seeded.exampleIds[0]}}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo(502)
            .jsonPath("$.code").isEqualTo("BAD_GATEWAY")
            .jsonPath("$.message").isEqualTo("AI 스토리 생성 요청에 실패했습니다.")

        check(storyRepository.count() == 0L)
        val session = sessionRepository.findById(seeded.sessionId).orElseThrow()
        check(session.status == StoryCreationSessionStatus.STORYLINES_GENERATED)
    }

    @Test
    fun `스토리 생성 API는 Bad Gateway 응답을 문서화한다`() {
        restTestClient.get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paths['/api/v1/stories/simple'].post.responses['502'].description")
            .isEqualTo("AI 서버 요청 실패")
    }

    private fun seedGeneratedSession(): SeededSession {
        val session = sessionRepository.save(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val tags = listOf(
            seedTag(SimpleStoryTagCategory.GENRE, "다크 판타지", 10),
            seedTag(SimpleStoryTagCategory.GENRE, "정치극", 11),
            seedTag(SimpleStoryTagCategory.PROTAGONIST, "신중한", 10),
        )
        sessionTagRepository.saveAll(
            tags.map { tag -> StoryCreationSessionTag(creationSession = session, tag = tag) },
        )
        val examples = exampleRepository.saveAll(
            (1..3).map { order ->
                StoryCreationExample(
                    creationSession = session,
                    exampleText = "스토리라인 $order",
                    exampleOrder = order.toShort(),
                )
            },
        )
        return SeededSession(session.id, examples.map { it.id })
    }

    private data class SeededSession(
        val sessionId: Long,
        val exampleIds: List<Long>,
    )

    private fun seedTag(
        category: SimpleStoryTagCategory,
        name: String,
        sortOrder: Int,
    ): StoryCreationTag =
        tagRepository.save(
            StoryCreationTag(
                category = category,
                name = name,
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = sortOrder,
            ),
        )

    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun storyAiClient(): CapturingStoryAiClient = CapturingStoryAiClient()
    }

    class CapturingStoryAiClient : StoryAiClient {
        var lastRequest: AiStorylinesRequest? = null
            private set
        var lastCompileRequest: AiStoryCompileRequest? = null
            private set
        var fail: Boolean = false
        var emptyRecommendedInfos: Boolean = false
        var compileFail: Boolean = false
        var compileTitle: String = "잿빛 왕관"
        var compileOneLineIntro: String = "무너진 왕국에서 진실을 좇는다."
        var transactionActiveDuringCall: Boolean? = null
            private set
        var compileTransactionActive: Boolean? = null
            private set

        override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse {
            lastRequest = request
            transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive()
            if (fail) {
                throw IllegalStateException("AI failure")
            }

            return AiStorylinesResponse(
                stories = (1..3).map { index ->
                    AiStoryItem(
                        id = index,
                        story = "생성 스토리 $index",
                        recommendedInfos = if (emptyRecommendedInfos) {
                            emptyList()
                        } else {
                            (1..3).map { infoIndex -> "추가 정보 $index-$infoIndex" }
                        },
                    )
                },
            )
        }

        override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse {
            lastCompileRequest = request
            compileTransactionActive = TransactionSynchronizationManager.isActualTransactionActive()
            if (compileFail) {
                throw IllegalStateException("AI failure")
            }

            return AiStoryCompileResponse(
                stories = AiStoryMeta(
                    title = compileTitle,
                    oneLineIntro = compileOneLineIntro,
                    description = "역병과 반란으로 무너진 왕국 이야기.",
                ),
                storySettings = AiStorySettings(
                    worldSetting = "# 세계관\n아르덴 왕국...",
                    characterSetting = "# 등장인물\n레이...",
                    userRoleSetting = "# 주인공\n견습 기사...",
                    ruleSetting = "# 전개 규칙\n정치 음모...",
                ),
                storyStartSettings = AiStoryStartSettings(
                    name = "선왕의 장례식 날",
                    startSituation = "장례식이 끝난 늦은 밤...",
                    prologue = "잿빛 비가 사흘째...",
                ),
                storySuggestedInputs = listOf(
                    "레이에게 문을 열어준다",
                    "경계하며 누구냐고 묻는다",
                    "침묵한다",
                ),
            )
        }

        fun reset() {
            lastRequest = null
            lastCompileRequest = null
            fail = false
            emptyRecommendedInfos = false
            compileFail = false
            compileTitle = "잿빛 왕관"
            compileOneLineIntro = "무너진 왕국에서 진실을 좇는다."
            transactionActiveDuringCall = null
            compileTransactionActive = null
        }
    }
}
