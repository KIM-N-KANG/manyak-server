package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
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
 * GET·PUT /api/v1/stories/{storyId}/endings 통합 검증(KNK-419).
 * - 조회(GET)는 공개. 교체(PUT)는 인증 필수 + 스토리 소유자만(비소유자·게스트 스토리 403, 무토큰 401).
 * - 엔딩은 시작 설정(start_setting) 하위다: 시작 설정이 없는 스토리에 교체하면 409.
 * - 교체 계약: 전체 대체(배열 순서=표시 순서, sort_order 1-based), 최대 10·필드·null 원소·래퍼 누락 검증(400), 스토리 없음 404.
 * - 노출: 스토리 상세(GET /stories/{id})에 endings 포함.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryEndingAuthoringIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyStartSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var storyEndingRepository: StoryEndingRepository
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

    // 엔딩은 시작 설정 하위이므로, 교체 저장 대상 스토리는 시작 설정을 갖춰야 한다(기본).
    private fun seedStory(ownerId: Long?, withStartSetting: Boolean = true): String {
        val story = storyRepository.save(Story(title = "테스트 스토리", userId = ownerId))
        if (withStartSetting) {
            storyStartSettingRepository.save(StoryStartSetting(story = story, name = "시작 설정"))
        }
        return story.publicId.toString()
    }

    private fun item(
        title: String,
        content: String = "결말 내용",
        conditionText: String? = null,
        enabled: Boolean? = null,
    ): String {
        val parts = mutableListOf(""""title":"$title"""", """"content":"$content"""")
        if (conditionText != null) parts += """"conditionText":"$conditionText""""
        if (enabled != null) parts += """"enabled":$enabled"""
        return "{" + parts.joinToString(",") + "}"
    }

    private fun putBody(storyId: String, body: String, token: String?) =
        restTestClient.put()
            .uri("/api/v1/stories/$storyId/endings")
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .body(body)
            .exchange()

    private fun putEndings(storyId: String, itemsJson: String, token: String?) =
        putBody(storyId, """{"endings":[$itemsJson]}""", token)

    private fun getEndings(storyId: String) =
        restTestClient.get().uri("/api/v1/stories/$storyId/endings").exchange()

    // ── 조회(공개) ──

    @Test
    fun `엔딩이 없으면 빈 배열을 반환한다(공개 조회)`() {
        getEndings(seedStory(ownerId = null))
            .expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `시작 설정이 없어도 조회는 빈 배열이다(공개 조회)`() {
        getEndings(seedStory(ownerId = null, withStartSetting = false))
            .expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    // ── 교체 인증·소유권 ──

    @Test
    fun `소유자는 교체 저장할 수 있고 sort_order는 1부터다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)

        putEndings(storyId, "${item("해피 엔딩")},${item("배드 엔딩")}", tokenFor(owner))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].title").isEqualTo("해피 엔딩")
            .jsonPath("$[0].sortOrder").isEqualTo(1)
            .jsonPath("$[1].sortOrder").isEqualTo(2)

        getEndings(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(2)
    }

    @Test
    fun `조건 텍스트와 활성화 여부가 왕복된다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)

        putEndings(
            storyId,
            item("숨겨진 엔딩", conditionText = "신뢰도 100 이상", enabled = false),
            tokenFor(owner),
        )
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].conditionText").isEqualTo("신뢰도 100 이상")
            .jsonPath("$[0].enabled").isEqualTo(false)
    }

    @Test
    fun `조건 텍스트를 생략하면 null이고 활성화 기본값은 true다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)

        putEndings(storyId, item("기본 엔딩"), tokenFor(owner))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].conditionText").isEmpty
            .jsonPath("$[0].enabled").isEqualTo(true)
    }

    @Test
    fun `토큰 없이 교체하면 401이다`() {
        putEndings(seedStory(ownerId = saveUser().id), item("엔딩"), token = null)
            .expectStatus().isUnauthorized
    }

    @Test
    fun `다른 사용자가 교체하면 403이고 기존이 보존된다`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val storyId = seedStory(ownerId = owner.id)
        putEndings(storyId, item("주인 엔딩"), tokenFor(owner)).expectStatus().isOk

        putEndings(storyId, item("침입 엔딩"), tokenFor(other)).expectStatus().isForbidden

        getEndings(storyId).expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].title").isEqualTo("주인 엔딩")
    }

    @Test
    fun `게스트 스토리(소유자 없음)는 교체할 수 없다(403)`() {
        putEndings(seedStory(ownerId = null), item("엔딩"), tokenFor(saveUser()))
            .expectStatus().isForbidden
    }

    @Test
    fun `소유자가 다시 교체하면 기존을 대체한다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putEndings(storyId, "${item("옛1")},${item("옛2")}", token).expectStatus().isOk

        putEndings(storyId, item("새 엔딩"), token).expectStatus().isOk

        getEndings(storyId).expectStatus().isOk.expectBody()
            .jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].title").isEqualTo("새 엔딩")
    }

    @Test
    fun `소유자가 빈 배열로 교체하면 전부 삭제된다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putEndings(storyId, item("엔딩"), token).expectStatus().isOk

        putBody(storyId, """{"endings":[]}""", token).expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)

        getEndings(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    // ── 시작 설정 없음 ──

    @Test
    fun `시작 설정이 없는 스토리에 교체하면 409다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id, withStartSetting = false)

        putEndings(storyId, item("엔딩"), tokenFor(owner)).expectStatus().isEqualTo(409)
    }

    // ── 검증(400): 인증·소유는 통과, 본문만 문제 ──

    @Test
    fun `10개를 넘기면 400이다`() {
        val owner = saveUser()
        val eleven = (1..11).joinToString(",") { item("엔딩$it") }
        putEndings(seedStory(ownerId = owner.id), eleven, tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `제목이 비어 있으면 400이다`() {
        val owner = saveUser()
        putEndings(seedStory(ownerId = owner.id), item(title = ""), tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `내용이 비어 있으면 400이다`() {
        val owner = saveUser()
        putEndings(seedStory(ownerId = owner.id), item(title = "엔딩", content = ""), tokenFor(owner))
            .expectStatus().isBadRequest
    }

    @Test
    fun `endings에 null 원소가 있으면 400이다`() {
        val owner = saveUser()
        putBody(seedStory(ownerId = owner.id), """{"endings":[null]}""", tokenFor(owner))
            .expectStatus().isBadRequest
    }

    @Test
    fun `endings 필드가 누락되면 400이고 기존 엔딩을 지우지 않는다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putEndings(storyId, item("보존될 엔딩"), token).expectStatus().isOk

        putBody(storyId, "{}", token).expectStatus().isBadRequest

        getEndings(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(1)
    }

    // ── 스토리 없음 ──

    @Test
    fun `없는 스토리에 조회하면 404다`() {
        getEndings(UUID.randomUUID().toString()).expectStatus().isNotFound
    }

    @Test
    fun `없는 스토리에 교체하면 404다`() {
        putEndings(UUID.randomUUID().toString(), item("엔딩"), tokenFor(saveUser())).expectStatus().isNotFound
    }

    // ── stale Bearer: GET(공개)은 무시, PUT(인증)은 401 ──

    @Test
    fun `조회는 만료 Bearer 헤더가 붙어도 동작하고 교체는 401이다`() {
        val storyId = seedStory(ownerId = saveUser().id)

        restTestClient.get().uri("/api/v1/stories/$storyId/endings")
            .header("Authorization", "Bearer stale.access.token")
            .exchange().expectStatus().isOk

        putBody(storyId, """{"endings":[${item("엔딩")}]}""", token = "stale.access.token")
            .expectStatus().isUnauthorized
    }

    // ── 상세 노출 ──

    @Test
    fun `스토리 상세에 엔딩이 포함된다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        putEndings(storyId, item("상세 노출 엔딩"), tokenFor(owner)).expectStatus().isOk

        restTestClient.get().uri("/api/v1/stories/$storyId").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.endings.length()").isEqualTo(1)
            .jsonPath("$.endings[0].title").isEqualTo("상세 노출 엔딩")

        val story = storyRepository.findByPublicIdAndDeletedAtIsNull(UUID.fromString(storyId))!!
        val startSetting = storyStartSettingRepository.findByStoryId(story.id)!!
        assertThat(storyEndingRepository.findByStartSettingIdOrderBySortOrderAsc(startSetting.id)).hasSize(1)
    }
}
