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
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.story.service.StoryPublicSnapshotService
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
    @Autowired private lateinit var snapshotService: StoryPublicSnapshotService
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
    fun `정지된 소유자의 수정 요청은 403이고 탈퇴한 소유자는 401이며 값이 바뀌지 않는다`() {
        // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499). 공개 전환이 콘텐츠 공개 행위라 수정 API도 공통 게이트 대상이다.
        val suspended = userRepository.save(User(nickname = "정지자", status = UserStatus.SUSPENDED))
        val suspendedStory = seedStory(userId = suspended.id)

        restTestClient.patch().uri("/api/v1/stories/${suspendedStory.publicId}")
            .header("Authorization", "Bearer ${tokenFor(suspended)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PUBLIC","title":"바뀐 제목"}""")
            .exchange().expectStatus().isForbidden

        val deleted = userRepository.save(User(nickname = "탈퇴자", status = UserStatus.DELETED))
        val deletedStory = seedStory(userId = deleted.id)

        restTestClient.patch().uri("/api/v1/stories/${deletedStory.publicId}")
            .header("Authorization", "Bearer ${tokenFor(deleted)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PUBLIC","title":"바뀐 제목"}""")
            .exchange().expectStatus().isUnauthorized

        val reloadedSuspended = storyRepository.findById(suspendedStory.id).get()
        val reloadedDeleted = storyRepository.findById(deletedStory.id).get()
        assertEquals(suspendedStory.title, reloadedSuspended.title)
        assertEquals(suspendedStory.visibility, reloadedSuspended.visibility)
        assertEquals(deletedStory.title, reloadedDeleted.title)
        assertEquals(deletedStory.visibility, reloadedDeleted.visibility)
    }

    @Test
    fun `소유자는 수정 API로 공개 전환하고 되돌릴 수 있으며 읽기 가시성에 즉시 반영된다`() {
        // 공개 전환은 별도 엔드포인트가 아니라 이 수정 API의 visibility 부분 갱신이다(스펙 §4-3-8·B26, KNK-1021).
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        val other = userRepository.save(User(nickname = "타인", status = UserStatus.ACTIVE))
        val story = seedStory(userId = owner.id)
        storyRepository.save(story.apply { visibility = StoryVisibility.PRIVATE })

        // 비공개인 동안에는 타인이 상세를 볼 수 없다(존재 여부 비노출 404).
        restTestClient.get().uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${tokenFor(other)}")
            .exchange().expectStatus().isNotFound

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${tokenFor(owner)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PUBLIC"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // 응답(편집 폼)에도 실려 폼 왕복이 보장된다.
            .jsonPath("$.visibility").isEqualTo("PUBLIC")

        assertEquals(StoryVisibility.PUBLIC, storyRepository.findById(story.id).get().visibility)

        // 전환 즉시 읽기 가시성에 반영된다 — 타인이 이제 상세를 볼 수 있다.
        restTestClient.get().uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${tokenFor(other)}")
            .exchange().expectStatus().isOk

        // 되돌림(PUBLIC → PRIVATE)도 같은 경로다.
        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${tokenFor(owner)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PRIVATE"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.visibility").isEqualTo("PRIVATE")

        restTestClient.get().uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${tokenFor(other)}")
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `게스트는 소유자 없는 스토리를 공개로 바꿀 수 없고 400이다`() {
        // 게스트 스토리는 게스트가 수정할 수 있지만(소유권 게이트 통과) 공개 전환만은 막는다.
        // 작성자 신원이 없어 카드에 작성자를 표기할 수 없고 소셜 기능의 책임 주체가 없기 때문이다.
        val story = seedStory(userId = null)
        storyRepository.save(story.apply { visibility = StoryVisibility.PRIVATE })

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PUBLIC"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("GUEST_CANNOT_PUBLISH")

        assertEquals(StoryVisibility.PRIVATE, storyRepository.findById(story.id).get().visibility)
    }

    @Test
    fun `게스트도 소유자 없는 스토리의 다른 필드는 수정할 수 있다`() {
        // 공개 전환만 막고 나머지 수정 경로는 그대로다(회귀 가드).
        val story = seedStory(userId = null)
        storyRepository.save(story.apply { visibility = StoryVisibility.PRIVATE })

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"title":"게스트가 고친 제목"}""")
            .exchange()
            .expectStatus().isOk

        assertEquals("게스트가 고친 제목", storyRepository.findById(story.id).get().title)
    }

    @Test
    fun `타인은 공개 전환할 수 없고 403이며 공개 범위가 그대로다`() {
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        val other = userRepository.save(User(nickname = "타인", status = UserStatus.ACTIVE))
        val story = seedStory(userId = owner.id)
        storyRepository.save(story.apply { visibility = StoryVisibility.PRIVATE })

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${tokenFor(other)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PUBLIC"}""")
            .exchange().expectStatus().isForbidden

        // 익명 요청도 회원 소유 스토리는 수정할 수 없다(기존 수정 API 소유권 관례).
        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PUBLIC"}""")
            .exchange().expectStatus().isForbidden

        assertEquals(StoryVisibility.PRIVATE, storyRepository.findById(story.id).get().visibility)
    }

    @Test
    fun `visibility를 생략하면 공개 범위는 바뀌지 않는다`() {
        // 부분 갱신 의미론: 미전송 필드는 유지다(null 명시 전송도 미전송과 동일).
        val story = seedStory(userId = null)
        storyRepository.save(story.apply { visibility = StoryVisibility.PRIVATE })

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"title":"새 제목","visibility":null}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.title").isEqualTo("새 제목")
            .jsonPath("$.visibility").isEqualTo("PRIVATE")

        assertEquals(StoryVisibility.PRIVATE, storyRepository.findById(story.id).get().visibility)
    }

    @Test
    fun `알 수 없는 visibility 값은 400이고 공개 범위는 그대로다`() {
        // 새 와이어 enum이 역직렬화 실패로 500이 되지 않는지 고정한다(GlobalExceptionHandler가 400으로 변환).
        val story = seedStory(userId = null)
        storyRepository.save(story.apply { visibility = StoryVisibility.PRIVATE })

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"EVERYONE"}""")
            .exchange().expectStatus().isBadRequest

        assertEquals(StoryVisibility.PRIVATE, storyRepository.findById(story.id).get().visibility)
    }

    @Test
    fun `PUBLISHED가 아닌 스토리의 공개 범위 변경은 400이고 값이 바뀌지 않는다`() {
        // 읽기 게이트가 PUBLISHED && PUBLIC이라 DRAFT에 PUBLIC을 저장하면 "공개인데 404"인 모순 상태가 된다.
        // 발행(status 전환)은 이 API의 범위가 아니므로 전환 자체를 400으로 거부한다(KNK-1021 리뷰).
        val story = seedStory(userId = null)
        storyRepository.save(story.apply { status = StoryStatus.DRAFT; visibility = StoryVisibility.PRIVATE })

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PUBLIC"}""")
            .exchange().expectStatus().isBadRequest

        val reloaded = storyRepository.findById(story.id).get()
        assertEquals(StoryVisibility.PRIVATE, reloaded.visibility)
        assertEquals(StoryStatus.DRAFT, reloaded.status)
    }

    @Test
    fun `PUBLISHED가 아닌 스토리도 visibility를 빼면 다른 필드는 정상 수정된다`() {
        // 게이트는 공개 범위 변경만 막는다. DRAFT 스토리의 일반 편집까지 막으면 레거시 스토리를 손볼 수 없다.
        val story = seedStory(userId = null)
        storyRepository.save(story.apply { status = StoryStatus.DRAFT; visibility = StoryVisibility.PRIVATE })

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"title":"초안도 고칠 수 있다"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.title").isEqualTo("초안도 고칠 수 있다")
            .jsonPath("$.visibility").isEqualTo("PRIVATE")

        assertEquals(StoryVisibility.PRIVATE, storyRepository.findById(story.id).get().visibility)
    }

    @Test
    fun `PUBLISHED가 아닌 스토리도 현재와 같은 visibility를 실어 보내면 통과한다`() {
        // 수정 폼 응답이 visibility를 싣기 때문에(폼 왕복) 프론트가 전체 폼을 되돌려보내면 값이 그대로 실려 온다.
        // 실제 전환이 아닌 이 무변경 전송까지 400으로 막으면 DRAFT 스토리는 폼 저장 자체가 불가능해진다.
        val story = seedStory(userId = null)
        storyRepository.save(story.apply { status = StoryStatus.DRAFT; visibility = StoryVisibility.PRIVATE })

        restTestClient.patch().uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"title":"폼 왕복 저장","visibility":"PRIVATE"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.title").isEqualTo("폼 왕복 저장")
            .jsonPath("$.visibility").isEqualTo("PRIVATE")
    }

    // ---- [KNK-1065] 마지막 공개 버전 스냅샷 갱신 ----

    /** 스토리 애그리거트 전체를 한 번에 덮는 PATCH 본문. */
    private fun patchAll(
        story: Story,
        startSettingPublicId: String,
        title: String,
        prologue: String,
        endingName: String,
        accessToken: String? = null,
    ) {
        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .apply { accessToken?.let { header("Authorization", "Bearer $it") } }
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "title": "$title",
                  "genres": ["$title 장르"],
                  "storySettings": {"worldSetting":"$title 세계관","characterSetting":"인물","userRoleSetting":"역할","ruleSetting":"규칙"},
                  "mainEvents": [{"name":"$title 사건","description":"설명","keySentence":"문장"}],
                  "startSettings": [
                    {
                      "id": "$startSettingPublicId",
                      "name": "장례식 날",
                      "prologue": "$prologue",
                      "startSituation": "늦은 밤",
                      "suggestedInputs": ["하나", "둘", "셋"],
                      "endings": [ {"name":"$endingName","requirement":{"minTurns":3,"achievementCondition":"조건"},"epilogue":"에필로그"} ]
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `공개 스토리를 수정하면 마지막 공개 버전 스냅샷이 함께 갱신된다`() {
        val story = seedStory(userId = null)
        val startSetting = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()

        patchAll(story, startSetting.publicId.toString(), "v2 제목", "v2 프롤로그", "v2 엔딩")

        val snapshot = snapshotService.findByStoryId(story.id)!!
        assertEquals("v2 제목", snapshot.title)
        assertEquals("v2 제목 장르", snapshot.genre)
        assertEquals("v2 제목 세계관", snapshot.storySettings.worldSetting)
        assertEquals(listOf("v2 제목 사건"), snapshot.mainEvents.map { it.name })
        // 시작 설정은 in-place 갱신이라 내부 id가 보존된다 — 진행 중 채팅이 이 id로 스냅샷을 찾는다.
        assertEquals(startSetting.id, snapshot.startSettings.single().id)
        assertEquals("v2 프롤로그", snapshot.startSettings.single().prologue)
        assertEquals(listOf("하나", "둘", "셋"), snapshot.startSettings.single().suggestedInputs)
        assertEquals(listOf("v2 엔딩"), snapshot.startSettings.single().endings.map { it.name })
        // 엔딩은 전체 교체로 행이 새로 생기므로, 스냅샷의 엔딩 id도 새 행을 가리켜야 한다.
        assertEquals(
            storyEndingRepository.findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(startSetting.id).single().id,
            snapshot.startSettings.single().endings.single().id,
        )
    }

    @Test
    fun `비공개로 전환하며 고친 값은 스냅샷에 들어가지 않는다`() {
        val story = seedStory(userId = null)
        val startSetting = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()
        patchAll(story, startSetting.publicId.toString(), "공개 제목", "공개 프롤로그", "공개 엔딩")

        // 같은 요청에서 비공개로 내리고 값을 고친다 — 갱신은 저장 시점의 공개 상태로 판정하므로 no-op이어야 한다.
        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PRIVATE","title":"비공개 개작 제목"}""")
            .exchange()
            .expectStatus().isOk

        val reloaded = storyRepository.findById(story.id).get()
        assertEquals("비공개 개작 제목", reloaded.title)
        assertEquals("공개 제목", snapshotService.findByStoryId(story.id)!!.title)
    }

    @Test
    fun `다시 공개로 되돌리면 그 시점 값이 새 스냅샷이 된다`() {
        // 공개 전환이 있는 시나리오라 회원 소유 스토리로 심는다(게스트는 PUBLIC 지정 불가, KNK-149).
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        val token = tokenFor(owner)
        val story = seedStory(userId = owner.id)
        val startSetting = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()
        patchAll(story, startSetting.publicId.toString(), "공개 제목", "공개 프롤로그", "공개 엔딩", accessToken = token)
        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PRIVATE","title":"개작 제목"}""")
            .exchange()
            .expectStatus().isOk

        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"visibility":"PUBLIC"}""")
            .exchange()
            .expectStatus().isOk

        // 다시 공개하면 개작본이 곧 현재 공개본이다.
        assertEquals("개작 제목", snapshotService.findByStoryId(story.id)!!.title)
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
