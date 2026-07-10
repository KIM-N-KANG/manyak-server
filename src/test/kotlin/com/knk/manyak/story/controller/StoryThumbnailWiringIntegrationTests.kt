package com.knk.manyak.story.controller

import com.knk.manyak.image.entity.ImagePreset
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 스토리 등록 시 확정된 썸네일이 상세·목록 응답에 `thumbnailUrl`로 실리는지 검증한다(스펙 §4-3-9).
 *
 * 자동 연결 규칙 자체는 [com.knk.manyak.story.service.StoryThumbnailLinkerIntegrationTests]가 덮는다.
 * 여기서는 등록 → 저장(`stories.thumbnail_image_key`) → URL 조합 → 와이어 노출의 배선을 확인한다.
 *
 * 매칭 장르의 썸네일을 1장만 심어 랜덤 선택을 결정적으로 만든다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["manyak.asset.image-base-url=https://cdn.test"])
class StoryThumbnailWiringIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var imagePresetRepository: ImagePresetRepository
    @Autowired private lateinit var storyCreationTagRepository: StoryCreationTagRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        val fantasy = storyCreationTagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "판타지",
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = 10,
            ),
        )
        imagePresetRepository.save(
            ImagePreset(imageKey = "thumb_0001", type = ImagePresetType.THUMBNAIL, genres = setOf(fantasy)),
        )
    }

    @AfterEach
    fun tearDown() = databaseCleaner.cleanAll()

    @Test
    fun `등록된 스토리의 상세 응답에 썸네일 URL이 실린다`() {
        val storyId = createStory(visibility = "PRIVATE")

        assertThat(storyRepository.findAll().single().thumbnailImageKey).isEqualTo("thumb_0001")

        restTestClient.get()
            .uri("/api/v1/stories/$storyId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.thumbnailUrl").isEqualTo("https://cdn.test/thumbnails/thumb_0001.png")
    }

    @Test
    fun `목록 항목 응답에도 썸네일 URL이 실린다`() {
        val storyId = createStory(visibility = "PUBLIC")

        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds": ["$storyId"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].thumbnailUrl").isEqualTo("https://cdn.test/thumbnails/thumb_0001.png")
    }

    /** 카탈로그에 후보가 없으면 연결하지 않는다 — 무관한 이미지를 임의로 붙이지 않는다. */
    @Test
    fun `썸네일 후보가 없으면 thumbnailUrl은 null이다`() {
        imagePresetRepository.deleteAll()

        val storyId = createStory(visibility = "PRIVATE")

        assertThat(storyRepository.findAll().single().thumbnailImageKey).isNull()

        restTestClient.get()
            .uri("/api/v1/stories/$storyId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.thumbnailUrl").doesNotExist()
    }

    private fun createStory(visibility: String): String {
        val body = """
            {
              "visibility": "$visibility",
              "title": "달빛 아래의 계약",
              "oneLineIntro": "기억을 잃은 마법사가 과거를 추적하는 이야기",
              "genres": ["판타지", "미스터리"],
              "storySettings": {
                "worldSetting": "몰락한 왕국 아르덴",
                "characterSetting": "기억을 잃은 마법사",
                "userRoleSetting": "과거를 쫓는 추적자",
                "ruleSetting": "마법은 대가를 요구한다"
              },
              "startSettings": [
                {
                  "name": "선왕의 장례식 날",
                  "prologue": "잿빛 비가 사흘째 왕성을 적신다",
                  "startSituation": "장례식이 끝난 늦은 밤",
                  "suggestedInputs": ["주변을 둘러본다", "봉인된 편지를 읽는다", "기사에게 말을 건다"],
                  "endings": []
                }
              ],
              "mainEvents": []
            }
        """.trimIndent()

        return restTestClient.post()
            .uri("/api/v1/stories/general")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()
            .let { String(it.responseBody!!) }
            .substringAfter("\"id\":\"")
            .substringBefore("\"")
    }
}
