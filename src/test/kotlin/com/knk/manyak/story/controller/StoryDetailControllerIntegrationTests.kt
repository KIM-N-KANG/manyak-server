package com.knk.manyak.story.controller

import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
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

    @BeforeEach
    fun setUp() {
        storySuggestedInputRepository.deleteAll()
        storyStartSettingRepository.deleteAll()
        storyRepository.deleteAll()
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
            .uri("/api/v1/stories/${story.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(story.id)
            .jsonPath("$.title").isEqualTo("잿빛 왕관")
            .jsonPath("$.shortDescription").isEqualTo("무너진 왕국에서 진실을 좇는다.")
            .jsonPath("$.detailedIntroduction").isEqualTo("역병과 반란으로 무너진 아르덴 왕국 이야기.")
            .jsonPath("$.genres.length()").isEqualTo(2)
            .jsonPath("$.genres[0]").isEqualTo("다크 판타지")
            .jsonPath("$.genres[1]").isEqualTo("정치극")
            .jsonPath("$.startSituationName").isEqualTo("선왕의 장례식 날")
            .jsonPath("$.conversationPrologue").isEqualTo("잿빛 비가 사흘째 왕성을 적신다.")
            .jsonPath("$.recommendedInputs.length()").isEqualTo(3)
            .jsonPath("$.recommendedInputs[0]").isEqualTo("레이에게 문을 열어준다")
            .jsonPath("$.recommendedInputs[2]").isEqualTo("침묵한다")
            .jsonPath("$.coverImageUrl").isEmpty
            .jsonPath("$.author").isEmpty
            .jsonPath("$.hashtags.length()").isEqualTo(0)
            .jsonPath("$.chatCount").isEqualTo(0)
            .jsonPath("$.likeCount").isEqualTo(0)
            .jsonPath("$.visibility").isEqualTo("PRIVATE")
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
            .jsonPath("$.message").isEqualTo("이야기를 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/999999")
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
            .uri("/api/v1/stories/${story.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.title").isEqualTo("설정 미완 스토리")
            .jsonPath("$.genres.length()").isEqualTo(0)
            .jsonPath("$.startSituationName").isEqualTo("")
            .jsonPath("$.conversationPrologue").isEqualTo("")
            .jsonPath("$.recommendedInputs.length()").isEqualTo(0)
    }
}
