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
 * 일반 모드 발행·공개설정 통합 검증(KNK-402, AI 없음).
 * - POST /api/v1/stories/{id}/publish : (인증·소유자) 초안(DRAFT)→PUBLISHED 발행 + 요청한 visibility 설정. 제목 필수(400), 이미 발행 409.
 * - PATCH /api/v1/stories/{id}/visibility : (인증·소유자) 공개↔비공개 토글.
 * - 효과(E2E): 발행 PUBLIC → 비회원 상세 200, 발행/토글 PRIVATE → 비회원 404·소유자 200.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryPublicationIntegrationTests {

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
        title: String = "내 초안",
        status: StoryStatus = StoryStatus.DRAFT,
        visibility: StoryVisibility = StoryVisibility.PRIVATE,
    ): Story = storyRepository.save(Story(title = title, userId = ownerId, status = status, visibility = visibility))

    private fun publish(storyId: String, visibility: String, token: String?) =
        restTestClient.post().uri("/api/v1/stories/$storyId/publish")
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .body("""{"visibility":"$visibility"}""")
            .exchange()

    private fun patchVisibility(storyId: String, visibility: String, token: String?) =
        restTestClient.patch().uri("/api/v1/stories/$storyId/visibility")
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .body("""{"visibility":"$visibility"}""")
            .exchange()

    private fun getDetail(storyId: String, token: String? = null) =
        restTestClient.get().uri("/api/v1/stories/$storyId")
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .exchange()

    // ── 발행 ──

    @Test
    fun `소유자는 초안을 PUBLIC으로 발행하고 비회원이 상세를 볼 수 있다`() {
        val owner = saveUser()
        val id = seedStory(owner.id, title = "잿빛 왕관").publicId.toString()

        publish(id, "PUBLIC", tokenFor(owner))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("PUBLISHED")
            .jsonPath("$.visibility").isEqualTo("PUBLIC")

        getDetail(id).expectStatus().isOk
    }

    @Test
    fun `소유자는 초안을 PRIVATE으로 발행하고 비회원은 404 소유자는 200이다`() {
        val owner = saveUser()
        val id = seedStory(owner.id).publicId.toString()

        publish(id, "PRIVATE", tokenFor(owner))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("PUBLISHED")
            .jsonPath("$.visibility").isEqualTo("PRIVATE")

        getDetail(id).expectStatus().isNotFound
        getDetail(id, tokenFor(owner)).expectStatus().isOk
    }

    @Test
    fun `발행은 무토큰 401 타인 403이다`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val id = seedStory(owner.id).publicId.toString()

        publish(id, "PUBLIC", token = null).expectStatus().isUnauthorized
        publish(id, "PUBLIC", tokenFor(other)).expectStatus().isForbidden
    }

    @Test
    fun `제목이 빈 초안은 발행할 수 없다(400)`() {
        val owner = saveUser()
        val id = seedStory(owner.id, title = "").publicId.toString()

        publish(id, "PUBLIC", tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `이미 발행된 스토리는 다시 발행할 수 없다(409)`() {
        val owner = saveUser()
        val id = seedStory(owner.id, status = StoryStatus.PUBLISHED, visibility = StoryVisibility.PUBLIC).publicId.toString()

        publish(id, "PUBLIC", tokenFor(owner)).expectStatus().isEqualTo(409)
    }

    @Test
    fun `없는 스토리 발행은 404다`() {
        publish(UUID.randomUUID().toString(), "PUBLIC", tokenFor(saveUser())).expectStatus().isNotFound
    }

    @Test
    fun `발행 요청의 visibility 값이 잘못되면 400이다`() {
        val owner = saveUser()
        val id = seedStory(owner.id).publicId.toString()

        publish(id, "NOPE", tokenFor(owner)).expectStatus().isBadRequest
    }

    // ── 공개여부 토글 ──

    @Test
    fun `소유자는 발행된 스토리의 공개여부를 토글한다`() {
        val owner = saveUser()
        val id = seedStory(owner.id, status = StoryStatus.PUBLISHED, visibility = StoryVisibility.PUBLIC).publicId.toString()

        // 공개 → 비공개: 비회원 상세가 404가 된다.
        patchVisibility(id, "PRIVATE", tokenFor(owner))
            .expectStatus().isOk
            .expectBody().jsonPath("$.visibility").isEqualTo("PRIVATE")
        getDetail(id).expectStatus().isNotFound

        // 다시 공개: 비회원 상세가 200이 된다.
        patchVisibility(id, "PUBLIC", tokenFor(owner)).expectStatus().isOk
        getDetail(id).expectStatus().isOk
    }

    @Test
    fun `공개여부 토글은 무토큰 401 타인 403이다`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val id = seedStory(owner.id, status = StoryStatus.PUBLISHED, visibility = StoryVisibility.PUBLIC).publicId.toString()

        patchVisibility(id, "PRIVATE", token = null).expectStatus().isUnauthorized
        patchVisibility(id, "PRIVATE", tokenFor(other)).expectStatus().isForbidden
    }

    @Test
    fun `없는 스토리 공개여부 토글은 404다`() {
        patchVisibility(UUID.randomUUID().toString(), "PUBLIC", tokenFor(saveUser())).expectStatus().isNotFound
    }
}
