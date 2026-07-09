package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 스토리 수정(GET /stories/{id}/edit · PATCH /stories/{id}, 스펙 §4-3-8·§4-8, KNK-404) 통합 테스트.
 * 편집 폼 왕복(통글 4필드 포함)·부분 갱신(보낸 필드만 교체)·소유권 403·검증 400을 확인한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryEditIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storySettingRepository: StorySettingRepository
    @Autowired private lateinit var storyStartSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository
    @Autowired private lateinit var storyMainEventRepository: StoryMainEventRepository
    @Autowired private lateinit var storyEndingRepository: StoryEndingRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach fun setUp() = databaseCleaner.cleanAll()
    @AfterEach fun tearDown() = databaseCleaner.cleanAll()

    private fun seedStory(userId: Long? = null): Story {
        val story = storyRepository.save(
            Story(userId = userId, title = "잿빛 왕관", oneLineIntro = "몰락한 왕국의 이야기", description = "설명", genre = "다크 판타지, 미스터리"),
        )
        storySettingRepository.save(
            StorySetting(story = story, worldSetting = "몰락한 왕국", characterSetting = "기사", userRoleSetting = "추적자", ruleSetting = "규칙"),
        )
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(story = story, name = "장례식 날", prologue = "비가 내린다", startSituation = "늦은 밤"),
        )
        storySuggestedInputRepository.saveAll(
            listOf("주변을 본다", "편지를 읽는다", "기사에게 묻는다").mapIndexed { i, t ->
                StorySuggestedInput(startSetting = startSetting, inputText = t, inputOrder = (i + 1).toShort())
            },
        )
        storyMainEventRepository.save(
            StoryMainEvent(story = story, name = "편지 발견", description = "편지를 찾는다", keySentence = "편지를 연다", sortOrder = 0),
        )
        storyEndingRepository.save(
            StoryEnding(startSetting = startSetting, name = "왕좌를 되찾다", minTurns = 10, achievementCondition = "왕좌를 되찾는다", epilogue = "대관식", sortOrder = 1),
        )
        return story
    }

    private fun tokenFor(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    @Test
    fun `소유자 없는 스토리의 수정 폼은 통글 4필드까지 전체를 왕복한다`() {
        val story = seedStory(userId = null)

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}/edit")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.title").isEqualTo("잿빛 왕관")
            .jsonPath("$.genres.length()").isEqualTo(2)
            .jsonPath("$.storySettings.worldSetting").isEqualTo("몰락한 왕국")
            .jsonPath("$.storySettings.ruleSetting").isEqualTo("규칙")
            // 시작 설정 복수화(KNK-515): 추천 입력·엔딩은 각 시작 설정에 중첩된다. 주요 사건은 스토리 스코프로 top-level.
            .jsonPath("$.startSettings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].id").isNotEmpty
            .jsonPath("$.startSettings[0].name").isEqualTo("장례식 날")
            .jsonPath("$.startSettings[0].suggestedInputs.length()").isEqualTo(3)
            .jsonPath("$.mainEvents[0].name").isEqualTo("편지 발견")
            .jsonPath("$.startSettings[0].endings[0].name").isEqualTo("왕좌를 되찾다")
            .jsonPath("$.startSettings[0].endings[0].requirement.minTurns").isEqualTo(10)
    }

    @Test
    fun `회원 소유 스토리의 수정 폼은 소유자만 조회하고 타인·익명은 403이다`() {
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        val other = userRepository.save(User(nickname = "타인", status = UserStatus.ACTIVE))
        val story = seedStory(userId = owner.id)

        restTestClient.get().uri("/api/v1/stories/${story.publicId}/edit")
            .header("Authorization", "Bearer ${tokenFor(owner)}")
            .exchange().expectStatus().isOk

        restTestClient.get().uri("/api/v1/stories/${story.publicId}/edit")
            .header("Authorization", "Bearer ${tokenFor(other)}")
            .exchange().expectStatus().isForbidden

        restTestClient.get().uri("/api/v1/stories/${story.publicId}/edit")
            .exchange().expectStatus().isForbidden
    }

    @Test
    fun `회원이 소유자 없는(게스트) 스토리의 수정 폼을 조회하면 403이다`() {
        // 교차 접근 차단(§4-5, KNK-480): 인증 회원은 게스트가 만든 NULL 소유 스토리를 수정할 수 없다(이관 후 접근).
        val member = userRepository.save(User(nickname = "회원", status = UserStatus.ACTIVE))
        val story = seedStory(userId = null)

        restTestClient.get().uri("/api/v1/stories/${story.publicId}/edit")
            .header("Authorization", "Bearer ${tokenFor(member)}")
            .exchange().expectStatus().isForbidden
    }

    @Test
    fun `없는 스토리의 수정 폼은 404다`() {
        restTestClient.get().uri("/api/v1/stories/00000000-0000-0000-0000-000000000000/edit")
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `부분 갱신은 보낸 필드만 교체하고 id 매칭 시작 설정은 identity 보존 후 자식 전체 교체한다`() {
        val story = seedStory(userId = null)
        val startSetting = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()
        val internalIdBefore = startSetting.id

        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "title": "새 제목",
                  "startSettings": [
                    {
                      "id": "${startSetting.publicId}",
                      "name": "장례식 날",
                      "prologue": "비가 내린다",
                      "startSituation": "늦은 밤",
                      "suggestedInputs": ["주변을 본다", "편지를 읽는다", "기사에게 묻는다"],
                      "endings": [ {"name":"새 엔딩","requirement":{"minTurns":3,"achievementCondition":"조건"},"epilogue":"에필로그"} ]
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.title").isEqualTo("새 제목")
            // 보내지 않은 스토리 설정은 유지된다.
            .jsonPath("$.storySettings.worldSetting").isEqualTo("몰락한 왕국")
            // id 매칭이면 같은 시작 설정을 in-place 갱신하고, 추천 입력·엔딩은 전체 교체된다.
            .jsonPath("$.startSettings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].id").isEqualTo(startSetting.publicId.toString())
            .jsonPath("$.startSettings[0].suggestedInputs.length()").isEqualTo(3)
            .jsonPath("$.startSettings[0].endings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].endings[0].name").isEqualTo("새 엔딩")
            .jsonPath("$.startSettings[0].endings[0].requirement.minTurns").isEqualTo(3)

        assertEquals("새 제목", storyRepository.findById(story.id).get().title)
        // id 매칭 in-place 갱신이므로 시작 설정 행 identity(내부 id)가 보존된다(진행 중 채팅의 start_setting_id 참조 유지).
        val afterSync = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()
        assertEquals(internalIdBefore, afterSync.id)
    }

    @Test
    fun `id 없는 시작 설정은 신규 추가되고 요청에서 빠진 기존은 삭제된다`() {
        val story = seedStory(userId = null)
        val existing = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()

        // 기존(id 지정) 대신 id 없는 새 시작 설정 하나만 보내면: 기존은 삭제, 새것 1개만 남는다.
        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "startSettings": [
                    {
                      "name": "새 시작",
                      "prologue": "새 프롤로그",
                      "startSituation": "새 상황",
                      "suggestedInputs": ["가", "나", "다"],
                      "endings": []
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.startSettings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].name").isEqualTo("새 시작")

        val remaining = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id)
        assertEquals(1, remaining.size)
        // 기존 시작 설정 행은 삭제되고 새 행으로 교체됐다(내부 id·publicId 모두 달라짐).
        assertEquals(false, remaining.single().publicId == existing.publicId)
        // 삭제된 시작 설정의 자식(추천 입력·엔딩)도 함께 정리된다.
        assertEquals(0, storyEndingRepository.findAll().count { it.startSetting.id == existing.id })
        assertEquals(0, storySuggestedInputRepository.findAll().count { it.startSetting.id == existing.id })
    }

    @Test
    fun `이 스토리에 속하지 않는 시작 설정 id로 수정하면 400이다`() {
        val story = seedStory(userId = null)

        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "startSettings": [
                    {
                      "id": "00000000-0000-0000-0000-000000000000",
                      "name": "남의 시작",
                      "prologue": "p",
                      "startSituation": "s",
                      "suggestedInputs": ["가", "나", "다"],
                      "endings": []
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `회원 소유 스토리는 타인이 수정하면 403이다`() {
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        val other = userRepository.save(User(nickname = "타인", status = UserStatus.ACTIVE))
        val story = seedStory(userId = owner.id)

        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${tokenFor(other)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{ "title": "탈취 시도" }""")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `요청 내 시작 설정 id가 중복되면 400이고 저장되지 않는다`() {
        // 전체 교체 계약에서 중복 id는 같은 행을 두 번 덮어 하나를 조용히 잃으므로 400으로 거부한다(silent wipe 방지).
        val story = seedStory(userId = null)
        val startSetting = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()

        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "startSettings": [
                    { "id": "${startSetting.publicId}", "name": "A", "prologue": "p", "startSituation": "s", "suggestedInputs": ["가","나","다"], "endings": [] },
                    { "id": "${startSetting.publicId}", "name": "B", "prologue": "p", "startSituation": "s", "suggestedInputs": ["가","나","다"], "endings": [] }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isBadRequest

        // 원본 시작 설정은 그대로 보존된다.
        assertEquals("장례식 날", storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single().name)
    }

    @Test
    fun `시작 설정의 추천 입력을 3개가 아닌 값으로 보내면 400이다`() {
        val story = seedStory(userId = null)

        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{ "startSettings": [ {"name":"n","prologue":"p","startSituation":"s","suggestedInputs":["하나","둘"],"endings":[]} ] }""",
            )
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `빈 제목으로 수정하면 400이다`() {
        val story = seedStory(userId = null)

        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{ "title": "   " }""")
            .exchange()
            .expectStatus().isBadRequest
    }
}
