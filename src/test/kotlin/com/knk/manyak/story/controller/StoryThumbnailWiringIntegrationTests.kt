package com.knk.manyak.story.controller

import com.knk.manyak.image.entity.ImagePreset
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
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

/** 새 스토리의 표지 null과 기존 프리셋 표지의 상세, 목록 노출을 검증한다. */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["manyak.asset.image-base-url=https://cdn.test"])
@org.springframework.context.annotation.Import(com.knk.manyak.support.SubmissionApprovalTestSupport::class)
class StoryThumbnailWiringIntegrationTests {
    @Autowired private lateinit var approvals: com.knk.manyak.support.SubmissionApprovalTestSupport
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name = "storyModerationExecutor")
    private lateinit var moderationExecutor: java.util.concurrent.Executor


    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var imagePresetRepository: ImagePresetRepository
    @Autowired private lateinit var storyCreationTagRepository: StoryCreationTagRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    // 공개(PUBLIC) 스토리는 회원만 만들 수 있다(KNK-149) — 등록을 이 회원 명의로 한다.
    private lateinit var accessToken: String

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
        val author = userRepository.save(User(nickname = "썸네일작가", status = UserStatus.ACTIVE))
        accessToken = jwtTokenProvider.issueAccessToken(author.publicId)
    }

    @AfterEach
    fun tearDown() = databaseCleaner.cleanAll()

    @Test
    fun `프리셋이 있어도 표지 없는 일반 제작의 키와 상세 표지는 null이다`() {
        val storyId = createStory(visibility = "PRIVATE")

        assertThat(storyRepository.findAll().single().thumbnailImageKey).isNull()

        restTestClient.get()
            .uri("/api/v1/stories/$storyId")
            // 비공개 스토리는 소유자만 읽는다 — 등록한 회원 명의로 조회한다.
            .header("Authorization", "Bearer $accessToken")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.thumbnailUrl").isEqualTo(null)
    }

    @Test
    fun `표지 없는 일반 제작의 목록 표지는 null이다`() {
        val storyId = createStory(visibility = "PUBLIC")

        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds": ["$storyId"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].thumbnailUrlSm").isEqualTo(null)
            .jsonPath("$[0].thumbnailUrl").doesNotExist()
    }

    /** 채팅 카드(46×62)도 목록과 같은 축소 변형을 공유한다(스펙 §4-3-9). */
    @Test
    fun `표지 없는 일반 제작의 채팅 카드 표지는 null이다`() {
        val storyId = createStory(visibility = "PUBLIC")

        val chatId = restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId": "$storyId"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()
            .let { String(it.responseBody!!) }
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

        restTestClient.post()
            .uri("/api/v1/chats/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"chatIds": ["$chatId"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].thumbnailUrlSm").isEqualTo(null)
    }

    @Test
    fun `썸네일 후보가 없으면 thumbnailUrl은 null이다`() {
        imagePresetRepository.deleteAll()

        val storyId = createStory(visibility = "PRIVATE")

        assertThat(storyRepository.findAll().single().thumbnailImageKey).isNull()

        restTestClient.get()
            .uri("/api/v1/stories/$storyId")
            // 비공개 스토리는 소유자만 읽는다 — 등록한 회원 명의로 조회한다.
            .header("Authorization", "Bearer $accessToken")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.thumbnailUrl").doesNotExist()
    }

    @Test
    fun `기존 프리셋 키가 저장된 스토리는 상세와 목록에 계속 노출된다`() {
        val story = storyRepository.save(
            com.knk.manyak.story.entity.Story(
                title = "기존 프리셋 스토리",
                visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC,
                thumbnailImageKey = "thumb_0001",
            ),
        )
        restTestClient.get().uri("/api/v1/stories/${story.publicId}")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.thumbnailUrl").isEqualTo("https://cdn.test/thumbnails/thumb_0001.png")
        restTestClient.post().uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds": ["${story.publicId}"]}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$[0].thumbnailUrlSm").isEqualTo("https://cdn.test/thumbnails/thumb_0001_sm.png")
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
            .header("Authorization", "Bearer $accessToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange().let { approvals.complete(it) }
            .expectStatus().isOk
            .expectBody()
            .returnResult()
            .let { String(it.responseBody!!) }
            .substringAfter("\"id\":\"")
            .substringBefore("\"")
    }
}
