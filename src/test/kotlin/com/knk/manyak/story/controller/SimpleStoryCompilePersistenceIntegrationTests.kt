package com.knk.manyak.story.controller

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
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.LorebookRepository
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
    }

    @TestConfiguration
    class FakeAiClientConfig {
        @Bean
        @Primary
        fun fakeStoryAiClient(): StoryAiClient = object : StoryAiClient {
            override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse =
                AiStorylinesResponse(stories = emptyList(), meta = AiResponseMeta())

            override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse {
                capturedRequest = request
                return AiStoryCompileResponse(
                    stories = AiStoryMeta("생성된 스토리", "한 줄 소개", "설명"),
                    storySettings = AiStorySettings("세계관", "캐릭터", "역할", "규칙"),
                    storyStartSettings = AiStoryStartSettings("시작", "상황", "프롤로그"),
                    storySuggestedInputs = listOf("추천1", "추천2", "추천3"),
                    storyMainEvents = mainEvents,
                    storyEndings = endings,
                    meta = AiResponseMeta(),
                )
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
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        capturedRequest = null
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
        val startSetting = storyStartSettingRepository.findByStoryId(story.id)!!
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
                """{"simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
}
