package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryMainEventRepository
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
 * GET·PUT /api/v1/stories/{storyId}/main-events 통합 검증(KNK-418, 스펙 §4-3-10).
 * - 조회(GET)는 공개. 교체(PUT)는 인증 필수 + 스토리 소유자만(비소유자·게스트 스토리 403, 무토큰 401).
 * - 교체 계약: 전체 대체(배열 순서=표시 순서), 최대 10·필드·null 원소·래퍼 누락 검증(400), 스토리 없음 404.
 * - 노출: 스토리 상세(GET /stories/{id})에 mainEvents 포함.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryMainEventIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyMainEventRepository: StoryMainEventRepository
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

    private fun seedStory(ownerId: Long?): String =
        storyRepository.save(Story(title = "테스트 스토리", userId = ownerId)).publicId.toString()

    private fun item(name: String, description: String = "설명", keySentence: String = "핵심 문장") =
        """{"name":"$name","description":"$description","keySentence":"$keySentence"}"""

    private fun putBody(storyId: String, body: String, token: String?) =
        restTestClient.put()
            .uri("/api/v1/stories/$storyId/main-events")
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .body(body)
            .exchange()

    private fun putMainEvents(storyId: String, itemsJson: String, token: String?) =
        putBody(storyId, """{"mainEvents":[$itemsJson]}""", token)

    private fun getMainEvents(storyId: String) =
        restTestClient.get().uri("/api/v1/stories/$storyId/main-events").exchange()

    // ── 조회(공개) ──

    @Test
    fun `주요 사건이 없으면 빈 배열을 반환한다(공개 조회)`() {
        getMainEvents(seedStory(ownerId = null))
            .expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    // ── 교체 인증·소유권 ──

    @Test
    fun `소유자는 교체 저장할 수 있다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)

        putMainEvents(storyId, "${item("첫 사건")},${item("둘째 사건")}", tokenFor(owner))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].name").isEqualTo("첫 사건")
            .jsonPath("$[0].sortOrder").isEqualTo(0)
            .jsonPath("$[1].sortOrder").isEqualTo(1)

        getMainEvents(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(2)
    }

    @Test
    fun `토큰 없이 교체하면 401이다`() {
        putMainEvents(seedStory(ownerId = saveUser().id), item("사건"), token = null)
            .expectStatus().isUnauthorized
    }

    @Test
    fun `다른 사용자가 교체하면 403이고 기존이 보존된다`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val storyId = seedStory(ownerId = owner.id)
        putMainEvents(storyId, item("주인 사건"), tokenFor(owner)).expectStatus().isOk

        putMainEvents(storyId, item("침입 사건"), tokenFor(other)).expectStatus().isForbidden

        getMainEvents(storyId).expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].name").isEqualTo("주인 사건")
    }

    @Test
    fun `게스트 스토리(소유자 없음)는 교체할 수 없다(403)`() {
        putMainEvents(seedStory(ownerId = null), item("사건"), tokenFor(saveUser()))
            .expectStatus().isForbidden
    }

    @Test
    fun `소유자가 다시 교체하면 기존을 대체한다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putMainEvents(storyId, "${item("옛1")},${item("옛2")}", token).expectStatus().isOk

        putMainEvents(storyId, item("새 사건"), token).expectStatus().isOk

        getMainEvents(storyId).expectStatus().isOk.expectBody()
            .jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].name").isEqualTo("새 사건")
    }

    @Test
    fun `소유자가 빈 배열로 교체하면 전부 삭제된다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putMainEvents(storyId, item("사건"), token).expectStatus().isOk

        putBody(storyId, """{"mainEvents":[]}""", token).expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)

        getMainEvents(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    // ── 검증(400): 인증·소유는 통과, 본문만 문제 ──

    @Test
    fun `10개를 넘기면 400이다`() {
        val owner = saveUser()
        val eleven = (1..11).joinToString(",") { item("사건$it") }
        putMainEvents(seedStory(ownerId = owner.id), eleven, tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `이름이 비어 있으면 400이다`() {
        val owner = saveUser()
        putMainEvents(seedStory(ownerId = owner.id), item(name = ""), tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `mainEvents에 null 원소가 있으면 400이다`() {
        val owner = saveUser()
        putBody(seedStory(ownerId = owner.id), """{"mainEvents":[null]}""", tokenFor(owner))
            .expectStatus().isBadRequest
    }

    @Test
    fun `mainEvents 필드가 누락되면 400이고 기존 사건을 지우지 않는다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putMainEvents(storyId, item("보존될 사건"), token).expectStatus().isOk

        putBody(storyId, "{}", token).expectStatus().isBadRequest

        getMainEvents(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(1)
    }

    // ── 스토리 없음 ──

    @Test
    fun `없는 스토리에 조회하면 404다`() {
        getMainEvents(UUID.randomUUID().toString()).expectStatus().isNotFound
    }

    @Test
    fun `없는 스토리에 교체하면 404다`() {
        putMainEvents(UUID.randomUUID().toString(), item("사건"), tokenFor(saveUser())).expectStatus().isNotFound
    }

    // ── stale Bearer: GET(공개)은 무시, PUT(인증)은 401 ──

    @Test
    fun `조회는 만료 Bearer 헤더가 붙어도 동작하고 교체는 401이다`() {
        val storyId = seedStory(ownerId = saveUser().id)

        restTestClient.get().uri("/api/v1/stories/$storyId/main-events")
            .header("Authorization", "Bearer stale.access.token")
            .exchange().expectStatus().isOk

        putBody(storyId, """{"mainEvents":[${item("사건")}]}""", token = "stale.access.token")
            .expectStatus().isUnauthorized
    }

    // ── 상세 노출 ──

    @Test
    fun `스토리 상세에 주요 사건이 포함된다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        putMainEvents(storyId, item("상세 노출 사건"), tokenFor(owner)).expectStatus().isOk

        restTestClient.get().uri("/api/v1/stories/$storyId").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.mainEvents.length()").isEqualTo(1)
            .jsonPath("$.mainEvents[0].name").isEqualTo("상세 노출 사건")

        val story = storyRepository.findByPublicIdAndDeletedAtIsNull(UUID.fromString(storyId))!!
        assertThat(storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)).hasSize(1)
    }
}
