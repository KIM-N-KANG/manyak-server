package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 일반 제작 단발 등록(`POST /stories/general`, 스펙 §4-3-8·§4-8) 통합 테스트.
 * 검증 후 그대로 저장(AI 비호출)·인증 선택 귀속·기본 PRIVATE·엔딩 이름기반 2파라미터 왕복을 확인한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GeneralStoryCreationIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storySettingRepository: StorySettingRepository
    @Autowired private lateinit var storyMainEventRepository: StoryMainEventRepository
    @Autowired private lateinit var storyEndingRepository: StoryEndingRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach fun setUp() = databaseCleaner.cleanAll()
    @AfterEach fun tearDown() = databaseCleaner.cleanAll()

    private fun body(visibility: String? = null): String {
        val visibilityLine = visibility?.let { "\"visibility\": \"$it\",\n" }.orEmpty()
        return """
            {
              $visibilityLine
              "title": "달빛 아래의 계약",
              "oneLineIntro": "기억을 잃은 마법사가 과거를 추적하는 이야기",
              "description": "주요 내용",
              "genres": ["판타지", "미스터리"],
              "storySettings": {
                "worldSetting": "몰락한 왕국 아르덴",
                "characterSetting": "기억을 잃은 마법사",
                "userRoleSetting": "과거를 쫓는 추적자",
                "ruleSetting": "마법은 대가를 요구한다"
              },
              "startSetting": {
                "name": "선왕의 장례식 날",
                "prologue": "잿빛 비가 사흘째 왕성을 적신다",
                "startSituation": "장례식이 끝난 늦은 밤"
              },
              "suggestedInputs": ["주변을 둘러본다", "봉인된 편지를 읽는다", "기사에게 말을 건다"],
              "mainEvents": [
                {"name": "편지 발견", "description": "다락방에서 봉인된 편지를 찾는다", "keySentence": "봉인된 편지를 연다"}
              ],
              "endings": [
                {"name": "왕좌를 되찾다", "requirement": {"minTurns": 10, "achievementCondition": "반란군을 규합해 왕좌를 되찾는다"}, "epilogue": "대관식을 장엄하게 묘사한다"}
              ]
            }
        """.trimIndent()
    }

    @Test
    fun `익명 등록은 소유자 없이 기본 PRIVATE로 저장되고 응답은 간편 제작과 동일하다`() {
        restTestClient.post()
            .uri("/api/v1/stories/general")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body())
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.id").isNotEmpty
            .jsonPath("$.title").isEqualTo("달빛 아래의 계약")
            .jsonPath("$.genres.length()").isEqualTo(2)
            .jsonPath("$.startSetting.name").isEqualTo("선왕의 장례식 날")
            .jsonPath("$.startSetting.startSituation").isEqualTo("장례식이 끝난 늦은 밤")

        val story = storyRepository.findAll().single()
        assertNull(story.userId)
        assertEquals(StoryStatus.PUBLISHED, story.status)
        assertEquals(StoryVisibility.PRIVATE, story.visibility)
        assertEquals("판타지, 미스터리", story.genre)

        val setting = storySettingRepository.findAll().single()
        assertEquals("몰락한 왕국 아르덴", setting.worldSetting)
        assertEquals("마법은 대가를 요구한다", setting.ruleSetting)
        assertEquals(1, storyMainEventRepository.findAll().size)
        assertEquals(1, storyEndingRepository.findAll().size)
    }

    @Test
    fun `소유자 없는 스토리는 UUID로 상세 조회되고 엔딩이 이름기반 2파라미터로 왕복된다`() {
        restTestClient.post()
            .uri("/api/v1/stories/general")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body())
            .exchange()
            .expectStatus().isCreated

        val story = storyRepository.findAll().single()

        // 게스트(익명) 요청도 소유자 없는 스토리는 UUID로 읽는다(§4-3-1, isReadableBy).
        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.mainEvents.length()").isEqualTo(1)
            .jsonPath("$.mainEvents[0].name").isEqualTo("편지 발견")
            .jsonPath("$.endings.length()").isEqualTo(1)
            .jsonPath("$.endings[0].name").isEqualTo("왕좌를 되찾다")
            .jsonPath("$.endings[0].requirement.minTurns").isEqualTo(10)
            .jsonPath("$.endings[0].requirement.achievementCondition").isEqualTo("반란군을 규합해 왕좌를 되찾는다")
            .jsonPath("$.endings[0].epilogue").isEqualTo("대관식을 장엄하게 묘사한다")
    }

    @Test
    fun `유효 토큰이면 요청자 소유로 귀속되고, 소유자 있는 PRIVATE 스토리는 익명 조회 시 404다`() {
        val owner = userRepository.save(User(nickname = "제작자", status = UserStatus.ACTIVE))
        val token = jwtTokenProvider.issueAccessToken(owner.publicId)

        restTestClient.post()
            .uri("/api/v1/stories/general")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body())
            .exchange()
            .expectStatus().isCreated

        val story = storyRepository.findAll().single()
        assertEquals(owner.id, story.userId)

        // 소유자는 자신의 PRIVATE 스토리를 읽는다.
        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk

        // 익명 요청은 소유자 있는 PRIVATE 스토리를 존재 노출 없이 404로 받는다.
        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `visibility PUBLIC로 등록하면 익명 조회가 허용된다`() {
        restTestClient.post()
            .uri("/api/v1/stories/general")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body(visibility = "PUBLIC"))
            .exchange()
            .expectStatus().isCreated

        val story = storyRepository.findAll().single()
        assertEquals(StoryVisibility.PUBLIC, story.visibility)

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `추천 입력이 정확히 3개가 아니면 400이다`() {
        val invalid = body().replace(
            "\"suggestedInputs\": [\"주변을 둘러본다\", \"봉인된 편지를 읽는다\", \"기사에게 말을 건다\"]",
            "\"suggestedInputs\": [\"하나\", \"둘\"]",
        )

        restTestClient.post()
            .uri("/api/v1/stories/general")
            .contentType(MediaType.APPLICATION_JSON)
            .body(invalid)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `필수 스토리 설정 필드가 비면 400이다`() {
        val invalid = body().replace("\"worldSetting\": \"몰락한 왕국 아르덴\"", "\"worldSetting\": \"\"")

        restTestClient.post()
            .uri("/api/v1/stories/general")
            .contentType(MediaType.APPLICATION_JSON)
            .body(invalid)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `장르가 상한을 넘으면 컬럼 초과 전에 400으로 걸러진다`() {
        // 9개(상한 8 초과)의 장르는 join 시 stories.genre(VARCHAR(255)) 초과 위험이 있다. insert 전에 400으로 걸러야 한다.
        val longGenres = (1..9).joinToString(",") { "\"장르_%02d_기이이이인이름\"".format(it) }
        val invalid = body().replace("\"genres\": [\"판타지\", \"미스터리\"]", "\"genres\": [$longGenres]")

        restTestClient.post()
            .uri("/api/v1/stories/general")
            .contentType(MediaType.APPLICATION_JSON)
            .body(invalid)
            .exchange()
            .expectStatus().isBadRequest
    }
}
