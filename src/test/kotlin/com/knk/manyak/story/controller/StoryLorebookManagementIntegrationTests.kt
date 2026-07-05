package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
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
 * GET·PUT /api/v1/stories/{storyId}/lorebooks 통합 검증(KNK-421).
 * - 로어북은 장르 공용 카탈로그([Lorebook])이고, 스토리는 그 id를 참조(story_lorebooks)한다. 이 API는 참조 집합의 쓰기 경로다.
 * - 조회(GET)는 공개. 교체(PUT)는 인증 필수 + 스토리 소유자만(비소유자·게스트 스토리 403, 무토큰 401).
 * - 교체 계약: 보낸 id 목록으로 전체 대체(배열 순서=표시 순서, sort_order 1-based). 빈 배열이면 전부 해제.
 *   최대 10·필드 누락·null 원소·중복 id·없거나 비활성인 id는 400, 스토리 없음 404.
 * - 노출: 스토리 상세(GET /stories/{id})의 lorebooks 로도 노출된다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryLorebookManagementIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var lorebookRepository: LorebookRepository
    @Autowired private lateinit var storyLorebookRepository: StoryLorebookRepository
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

    private fun saveLorebook(name: String, genre: String? = null, active: Boolean = true): Long =
        lorebookRepository.save(Lorebook(name = name, genre = genre, content = "$name 본문", isActive = active)).id

    private fun seedStory(ownerId: Long?): String =
        storyRepository.save(Story(title = "테스트 스토리", userId = ownerId)).publicId.toString()

    private fun putBody(storyId: String, body: String, token: String?) =
        restTestClient.put()
            .uri("/api/v1/stories/$storyId/lorebooks")
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .body(body)
            .exchange()

    private fun putIds(storyId: String, idsCsv: String, token: String?) =
        putBody(storyId, """{"lorebookIds":[$idsCsv]}""", token)

    private fun getLorebooks(storyId: String) =
        restTestClient.get().uri("/api/v1/stories/$storyId/lorebooks").exchange()

    // ── 조회(공개) ──

    @Test
    fun `참조 로어북이 없으면 빈 배열을 반환한다(공개 조회)`() {
        getLorebooks(seedStory(ownerId = null))
            .expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    // ── 교체 인증·소유권 ──

    @Test
    fun `소유자는 참조 로어북을 배열 순서대로 교체 저장한다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val worldGlossary = saveLorebook("왕국 용어집", genre = "다크 판타지")
        val magicGlossary = saveLorebook("마법 용어집", genre = "다크 판타지")

        // 삽입 역순으로 보내 배열 순서가 표시 순서가 되는지 확인한다.
        putIds(storyId, "$magicGlossary,$worldGlossary", tokenFor(owner))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].name").isEqualTo("마법 용어집")
            .jsonPath("$[0].content").isEqualTo("마법 용어집 본문")
            .jsonPath("$[1].name").isEqualTo("왕국 용어집")

        getLorebooks(storyId).expectStatus().isOk.expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].name").isEqualTo("마법 용어집")
    }

    @Test
    fun `토큰 없이 교체하면 401이다`() {
        putIds(seedStory(ownerId = saveUser().id), saveLorebook("용어집").toString(), token = null)
            .expectStatus().isUnauthorized
    }

    @Test
    fun `다른 사용자가 교체하면 403이고 기존이 보존된다`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val storyId = seedStory(ownerId = owner.id)
        val a = saveLorebook("A")
        val b = saveLorebook("B")
        putIds(storyId, a.toString(), tokenFor(owner)).expectStatus().isOk

        putIds(storyId, b.toString(), tokenFor(other)).expectStatus().isForbidden

        getLorebooks(storyId).expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].name").isEqualTo("A")
    }

    @Test
    fun `게스트 스토리(소유자 없음)는 교체할 수 없다(403)`() {
        putIds(seedStory(ownerId = null), saveLorebook("용어집").toString(), tokenFor(saveUser()))
            .expectStatus().isForbidden
    }

    @Test
    fun `소유자가 다시 교체하면 기존을 대체한다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putIds(storyId, "${saveLorebook("옛1")},${saveLorebook("옛2")}", token).expectStatus().isOk

        putIds(storyId, saveLorebook("새 용어집").toString(), token).expectStatus().isOk

        getLorebooks(storyId).expectStatus().isOk.expectBody()
            .jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].name").isEqualTo("새 용어집")
    }

    @Test
    fun `소유자가 빈 배열로 교체하면 전부 해제된다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putIds(storyId, saveLorebook("용어집").toString(), token).expectStatus().isOk

        putBody(storyId, """{"lorebookIds":[]}""", token).expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)

        getLorebooks(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    // ── 검증(400) ──

    @Test
    fun `10개를 넘기면 400이다`() {
        val owner = saveUser()
        val eleven = (1..11).joinToString(",") { saveLorebook("용어집$it").toString() }
        putIds(seedStory(ownerId = owner.id), eleven, tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `중복 id를 보내면 400이다`() {
        val owner = saveUser()
        val id = saveLorebook("용어집")
        putIds(seedStory(ownerId = owner.id), "$id,$id", tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `존재하지 않는 로어북 id면 400이다`() {
        val owner = saveUser()
        putIds(seedStory(ownerId = owner.id), "999999", tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `비활성 로어북 id면 400이다`() {
        val owner = saveUser()
        val inactive = saveLorebook("비활성 용어집", active = false)
        putIds(seedStory(ownerId = owner.id), inactive.toString(), tokenFor(owner)).expectStatus().isBadRequest
    }

    @Test
    fun `lorebookIds에 null 원소가 있으면 400이다`() {
        val owner = saveUser()
        putBody(seedStory(ownerId = owner.id), """{"lorebookIds":[null]}""", tokenFor(owner))
            .expectStatus().isBadRequest
    }

    @Test
    fun `lorebookIds 필드가 누락되면 400이고 기존 참조를 지우지 않는다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val token = tokenFor(owner)
        putIds(storyId, saveLorebook("보존될 용어집").toString(), token).expectStatus().isOk

        putBody(storyId, "{}", token).expectStatus().isBadRequest

        getLorebooks(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(1)
    }

    // ── 스토리 없음 ──

    @Test
    fun `없는 스토리에 조회하면 404다`() {
        getLorebooks(UUID.randomUUID().toString()).expectStatus().isNotFound
    }

    @Test
    fun `없는 스토리에 교체하면 404다`() {
        putIds(UUID.randomUUID().toString(), saveLorebook("용어집").toString(), tokenFor(saveUser()))
            .expectStatus().isNotFound
    }

    // ── stale Bearer: GET(공개)은 무시, PUT(인증)은 401 ──

    @Test
    fun `조회는 만료 Bearer 헤더가 붙어도 동작하고 교체는 401이다`() {
        val storyId = seedStory(ownerId = saveUser().id)

        restTestClient.get().uri("/api/v1/stories/$storyId/lorebooks")
            .header("Authorization", "Bearer stale.access.token")
            .exchange().expectStatus().isOk

        putBody(storyId, """{"lorebookIds":[${saveLorebook("용어집")}]}""", token = "stale.access.token")
            .expectStatus().isUnauthorized
    }

    // ── 상세 노출 ──

    @Test
    fun `스토리 상세에 참조 로어북이 포함된다`() {
        val owner = saveUser()
        val storyId = seedStory(ownerId = owner.id)
        val id = saveLorebook("상세 노출 용어집")
        putIds(storyId, id.toString(), tokenFor(owner)).expectStatus().isOk

        restTestClient.get().uri("/api/v1/stories/$storyId").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.lorebooks.length()").isEqualTo(1)
            .jsonPath("$.lorebooks[0].name").isEqualTo("상세 노출 용어집")

        val story = storyRepository.findByPublicIdAndDeletedAtIsNull(UUID.fromString(storyId))!!
        assertThat(storyLorebookRepository.findByStoryIdOrderBySortOrderAscIdAsc(story.id)).hasSize(1)
    }
}
