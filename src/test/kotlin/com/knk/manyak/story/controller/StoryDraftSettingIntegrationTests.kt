package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
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
import java.util.UUID

/**
 * 일반 모드 초안(draft) 기반 + 세계관 저장·조회 통합 검증(KNK-401).
 * - 초안 생성 POST /api/v1/stories : 인증 필수. status=DRAFT, visibility=PRIVATE로 생성.
 * - 세계관 GET·PUT /api/v1/stories/{id}/setting : 소유자만. PUT은 PATCH 의미(보낸 필드만 갱신, 없으면 생성).
 * - 공개 조회(GET /stories/{id}, POST /stories/batch)는 PUBLISHED이면서 PUBLIC인 스토리만 노출.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryDraftSettingIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(nickname: String = "작가"): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun tokenFor(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    private fun seedStory(
        ownerId: Long?,
        status: StoryStatus = StoryStatus.PUBLISHED,
        visibility: StoryVisibility = StoryVisibility.PUBLIC,
    ): Story = storyRepository.save(Story(title = "테스트 스토리", userId = ownerId, status = status, visibility = visibility))

    private fun postDraft(body: String, token: String?) =
        restTestClient.post().uri("/api/v1/stories")
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .body(body)
            .exchange()

    private fun putSetting(storyId: String, body: String, token: String?) =
        restTestClient.put().uri("/api/v1/stories/$storyId/setting")
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .body(body)
            .exchange()

    private fun getSetting(storyId: String, token: String?) =
        restTestClient.get().uri("/api/v1/stories/$storyId/setting")
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .exchange()

    // ── 초안 생성 ──

    @Test
    fun `초안 생성은 인증이 필요하다(무토큰 401)`() {
        postDraft("""{"title":"내 초안"}""", token = null).expectStatus().isUnauthorized
    }

    @Test
    fun `초안을 생성하면 201, DRAFT·PRIVATE로 저장되고 공개 조회에서 제외된다`() {
        val owner = saveUser()
        postDraft("""{"title":"내 초안"}""", tokenFor(owner)).expectStatus().isCreated

        // 방금 만든 초안을 소유자로 조회해 상태를 확인한다(테스트 DB는 비어 있어 소유 스토리가 하나뿐).
        val story = storyRepository.findAll().single { it.userId == owner.id }
        assertThat(story.status).isEqualTo(StoryStatus.DRAFT)
        assertThat(story.visibility).isEqualTo(StoryVisibility.PRIVATE)
        assertThat(story.title).isEqualTo("내 초안")

        // 초안(DRAFT)은 공개 상세 조회에서 404다.
        restTestClient.get().uri("/api/v1/stories/${story.publicId}").exchange().expectStatus().isNotFound
    }

    @Test
    fun `본문 없이도 초안을 생성할 수 있다`() {
        postDraft("{}", tokenFor(saveUser())).expectStatus().isCreated
    }

    // ── 세계관 저장·조회(소유자만) ──

    @Test
    fun `세계관 저장은 무토큰 401, 타인 403이다`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val id = seedStory(owner.id, StoryStatus.DRAFT, StoryVisibility.PRIVATE).publicId.toString()

        putSetting(id, """{"worldSetting":"W"}""", token = null).expectStatus().isUnauthorized
        putSetting(id, """{"worldSetting":"W"}""", tokenFor(other)).expectStatus().isForbidden
    }

    @Test
    fun `세계관 조회도 소유자만 가능하다(무토큰 401, 타인 403)`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val id = seedStory(owner.id, StoryStatus.DRAFT, StoryVisibility.PRIVATE).publicId.toString()

        getSetting(id, token = null).expectStatus().isUnauthorized
        getSetting(id, tokenFor(other)).expectStatus().isForbidden
    }

    @Test
    fun `소유자는 세계관을 저장하고 조회한다(없으면 생성)`() {
        val owner = saveUser()
        val token = tokenFor(owner)
        val id = seedStory(owner.id, StoryStatus.DRAFT, StoryVisibility.PRIVATE).publicId.toString()

        putSetting(id, """{"worldSetting":"몰락한 왕국","characterSetting":"주인공은 기사"}""", token)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.worldSetting").isEqualTo("몰락한 왕국")
            .jsonPath("$.characterSetting").isEqualTo("주인공은 기사")

        getSetting(id, token).expectStatus().isOk
            .expectBody().jsonPath("$.worldSetting").isEqualTo("몰락한 왕국")
    }

    @Test
    fun `세계관 저장은 PATCH 의미다(보낸 필드만 갱신)`() {
        val owner = saveUser()
        val token = tokenFor(owner)
        val id = seedStory(owner.id, StoryStatus.DRAFT, StoryVisibility.PRIVATE).publicId.toString()
        putSetting(id, """{"worldSetting":"W1","characterSetting":"C1"}""", token).expectStatus().isOk

        putSetting(id, """{"worldSetting":"W2"}""", token).expectStatus().isOk

        getSetting(id, token).expectStatus().isOk
            .expectBody()
            .jsonPath("$.worldSetting").isEqualTo("W2")
            .jsonPath("$.characterSetting").isEqualTo("C1")
    }

    @Test
    fun `세계관이 없으면 조회는 빈 값이다`() {
        val owner = saveUser()
        val id = seedStory(owner.id, StoryStatus.DRAFT, StoryVisibility.PRIVATE).publicId.toString()

        getSetting(id, tokenFor(owner)).expectStatus().isOk
            .expectBody().jsonPath("$.worldSetting").isEmpty
    }

    @Test
    fun `없는 스토리의 세계관 저장·조회는 404다`() {
        val token = tokenFor(saveUser())
        putSetting(UUID.randomUUID().toString(), """{"worldSetting":"W"}""", token).expectStatus().isNotFound
        getSetting(UUID.randomUUID().toString(), token).expectStatus().isNotFound
    }

    // ── 공개 조회 필터(PUBLISHED ∧ PUBLIC) ──

    @Test
    fun `공개 상세 조회는 PUBLISHED+PUBLIC만 노출한다`() {
        val published = seedStory(null, StoryStatus.PUBLISHED, StoryVisibility.PUBLIC).publicId.toString()
        val draft = seedStory(null, StoryStatus.DRAFT, StoryVisibility.PUBLIC).publicId.toString()
        val private = seedStory(null, StoryStatus.PUBLISHED, StoryVisibility.PRIVATE).publicId.toString()

        restTestClient.get().uri("/api/v1/stories/$published").exchange().expectStatus().isOk
        restTestClient.get().uri("/api/v1/stories/$draft").exchange().expectStatus().isNotFound
        restTestClient.get().uri("/api/v1/stories/$private").exchange().expectStatus().isNotFound
    }

    @Test
    fun `batch 조회는 DRAFT·PRIVATE를 제외한다`() {
        val published = seedStory(null, StoryStatus.PUBLISHED, StoryVisibility.PUBLIC).publicId.toString()
        val draft = seedStory(null, StoryStatus.DRAFT, StoryVisibility.PRIVATE).publicId.toString()
        val private = seedStory(null, StoryStatus.PUBLISHED, StoryVisibility.PRIVATE).publicId.toString()

        restTestClient.post().uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["$published","$draft","$private"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].id").isEqualTo(published)
    }
}
