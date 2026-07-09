package com.knk.manyak.story.controller

import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.support.DatabaseCleaner
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryDetailControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storyEndingRepository: StoryEndingRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `turnCount는 스토리의 미삭제 채팅 current_turn 합이다`() {
        val story = storyRepository.save(Story(title = "턴 집계 스토리", genre = "판타지", visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC, status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED))
        storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 3))
        storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 2))
        // 소프트 삭제된 채팅은 합산에서 제외한다.
        storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 5, deletedAt = Instant.now()))

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turnCount").isEqualTo(5)
    }

    @Test
    fun `스토리 상세 정보를 조회한다`() {
        val story = storyRepository.save(
            Story(
                title = "잿빛 왕관",
                oneLineIntro = "무너진 왕국에서 진실을 좇는다.",
                description = "역병과 반란으로 무너진 아르덴 왕국 이야기.",
                genre = "다크 판타지, 정치극",
            ),
        )
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "선왕의 장례식 날",
                prologue = "잿빛 비가 사흘째 왕성을 적신다.",
                startSituation = "장례식이 끝난 늦은 밤, 기사단 숙소.",
            ),
        )
        storySuggestedInputRepository.saveAll(
            listOf(
                StorySuggestedInput(startSetting = startSetting, inputText = "레이에게 문을 열어준다", inputOrder = 1.toShort()),
                StorySuggestedInput(startSetting = startSetting, inputText = "경계하며 누구냐고 묻는다", inputOrder = 2.toShort()),
                StorySuggestedInput(startSetting = startSetting, inputText = "침묵한다", inputOrder = 3.toShort()),
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // 응답 id는 순차 PK가 아니라 추측 불가능한 공개 식별자(public_id)다.
            .jsonPath("$.id").isEqualTo(story.publicId.toString())
            .jsonPath("$.title").isEqualTo("잿빛 왕관")
            .jsonPath("$.oneLineIntro").isEqualTo("무너진 왕국에서 진실을 좇는다.")
            .jsonPath("$.description").isEqualTo("역병과 반란으로 무너진 아르덴 왕국 이야기.")
            .jsonPath("$.genres.length()").isEqualTo(2)
            .jsonPath("$.genres[0]").isEqualTo("다크 판타지")
            .jsonPath("$.genres[1]").isEqualTo("정치극")
            .jsonPath("$.startSettings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].id").isEqualTo(startSetting.publicId.toString())
            .jsonPath("$.startSettings[0].name").isEqualTo("선왕의 장례식 날")
            .jsonPath("$.startSettings[0].prologue").isEqualTo("잿빛 비가 사흘째 왕성을 적신다.")
            .jsonPath("$.startSettings[0].startSituation").isEqualTo("장례식이 끝난 늦은 밤, 기사단 숙소.")
            .jsonPath("$.startSettings[0].suggestedInputs.length()").isEqualTo(3)
            .jsonPath("$.startSettings[0].suggestedInputs[0]").isEqualTo("레이에게 문을 열어준다")
            .jsonPath("$.startSettings[0].suggestedInputs[2]").isEqualTo("침묵한다")
            .jsonPath("$.thumbnailUrl").isEmpty
            .jsonPath("$.author").isEmpty
            .jsonPath("$.hashtags.length()").isEqualTo(0)
            .jsonPath("$.turnCount").isEqualTo(0)
            .jsonPath("$.likeCount").isEqualTo(0)
            .jsonPath("$.visibility").isEqualTo("PUBLIC")
            .jsonPath("$.status").isEqualTo("PUBLISHED")
    }

    @Test
    fun `존재하지 않는 스토리는 404로 응답한다`() {
        restTestClient.get()
            .uri("/api/v1/stories/999999")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/999999")
    }

    @Test
    fun `순차 PK(내부 id)로는 조회되지 않고 404로 통일된다 (IDOR 차단)`() {
        val story = storyRepository.save(Story(title = "공개 식별자만 노출하는 스토리"))

        // 내부 순차 PK를 추측해 접근해도 공개 식별자(UUID)가 아니므로 404로 통일된다.
        restTestClient.get()
            .uri("/api/v1/stories/${story.id}")
            .exchange()
            .expectStatus().isNotFound

        // 공개 식별자로는 정상 조회된다.
        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `시작 설정이 없는 스토리도 빈 시작 정보로 조회된다`() {
        val story = storyRepository.save(
            Story(
                title = "설정 미완 스토리",
                oneLineIntro = "한 줄 소개",
                description = null,
                genre = null,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.title").isEqualTo("설정 미완 스토리")
            .jsonPath("$.genres.length()").isEqualTo(0)
            .jsonPath("$.startSettings.length()").isEqualTo(0)
    }

    @Test
    fun `시작 설정이 여러 개면 등록 순서로 싣고 추천 입력·엔딩을 각 시작 설정에 종속시킨다`() {
        val story = storyRepository.save(
            Story(
                title = "복수 시작 설정",
                genre = "판타지",
                visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC,
                status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED,
            ),
        )
        val first = storyStartSettingRepository.save(StoryStartSetting(story = story, name = "첫 시작", prologue = "프롤로그1"))
        val second = storyStartSettingRepository.save(StoryStartSetting(story = story, name = "둘째 시작", prologue = "프롤로그2"))
        storySuggestedInputRepository.saveAll(
            listOf(
                StorySuggestedInput(startSetting = first, inputText = "가", inputOrder = 1.toShort()),
                StorySuggestedInput(startSetting = second, inputText = "나", inputOrder = 1.toShort()),
                StorySuggestedInput(startSetting = second, inputText = "다", inputOrder = 2.toShort()),
            ),
        )
        storyEndingRepository.save(
            StoryEnding(startSetting = second, name = "둘째의 엔딩", minTurns = 3, achievementCondition = "조건", epilogue = "에필로그", sortOrder = 1),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // 등록 순서(id 오름차순)로 실린다.
            .jsonPath("$.startSettings.length()").isEqualTo(2)
            .jsonPath("$.startSettings[0].id").isEqualTo(first.publicId.toString())
            .jsonPath("$.startSettings[0].name").isEqualTo("첫 시작")
            .jsonPath("$.startSettings[0].suggestedInputs.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].endings.length()").isEqualTo(0)
            .jsonPath("$.startSettings[1].id").isEqualTo(second.publicId.toString())
            .jsonPath("$.startSettings[1].name").isEqualTo("둘째 시작")
            // 추천 입력·엔딩은 각 시작 설정 스코프로 정확히 매핑된다.
            .jsonPath("$.startSettings[1].suggestedInputs.length()").isEqualTo(2)
            .jsonPath("$.startSettings[1].endings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[1].endings[0].name").isEqualTo("둘째의 엔딩")
    }
}
