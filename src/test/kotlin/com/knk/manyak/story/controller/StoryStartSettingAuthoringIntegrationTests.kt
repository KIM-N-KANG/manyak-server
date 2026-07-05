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
 * 일반 모드 시작설정 저작 통합 검증(KNK-460, AI 없음).
 * - GET·PUT /api/v1/stories/{id}/start-setting : (인증·소유자) 시작설정 조회·저장(PATCH 의미 부분저장, 없으면 생성).
 * - 저작 필드: name·prologue·startSituation·openingScene·firstAiMessage.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryStartSettingAuthoringIntegrationTests {

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

    private fun seedStory(ownerId: Long?): Story =
        storyRepository.save(Story(title = "내 초안", userId = ownerId, status = StoryStatus.DRAFT, visibility = StoryVisibility.PRIVATE))

    private fun putStartSetting(storyId: String, body: String, token: String?) =
        restTestClient.put().uri("/api/v1/stories/$storyId/start-setting")
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .body(body)
            .exchange()

    private fun getStartSetting(storyId: String, token: String?) =
        restTestClient.get().uri("/api/v1/stories/$storyId/start-setting")
            .let { if (token != null) it.header("Authorization", "Bearer $token") else it }
            .exchange()

    @Test
    fun `시작설정 저장은 무토큰 401 타인 403이다`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val id = seedStory(owner.id).publicId.toString()

        putStartSetting(id, """{"name":"시작"}""", token = null).expectStatus().isUnauthorized
        putStartSetting(id, """{"name":"시작"}""", tokenFor(other)).expectStatus().isForbidden
    }

    @Test
    fun `시작설정 조회는 소유자만 가능하다(무토큰 401 타인 403)`() {
        val owner = saveUser("주인")
        val other = saveUser("타인")
        val id = seedStory(owner.id).publicId.toString()

        getStartSetting(id, token = null).expectStatus().isUnauthorized
        getStartSetting(id, tokenFor(other)).expectStatus().isForbidden
    }

    @Test
    fun `소유자는 시작설정을 저장하고 조회한다(없으면 생성)`() {
        val owner = saveUser()
        val token = tokenFor(owner)
        val id = seedStory(owner.id).publicId.toString()

        putStartSetting(
            id,
            """{"name":"선왕의 장례식","prologue":"잿빛 비가 사흘째","openingScene":"장례식장","firstAiMessage":"당신은 문 앞에 선다."}""",
            token,
        )
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("선왕의 장례식")
            .jsonPath("$.openingScene").isEqualTo("장례식장")
            .jsonPath("$.firstAiMessage").isEqualTo("당신은 문 앞에 선다.")

        getStartSetting(id, token).expectStatus().isOk
            .expectBody().jsonPath("$.prologue").isEqualTo("잿빛 비가 사흘째")
    }

    @Test
    fun `시작설정 저장은 PATCH 의미다(보낸 필드만 갱신)`() {
        val owner = saveUser()
        val token = tokenFor(owner)
        val id = seedStory(owner.id).publicId.toString()
        putStartSetting(id, """{"name":"A","openingScene":"O1"}""", token).expectStatus().isOk

        putStartSetting(id, """{"openingScene":"O2"}""", token).expectStatus().isOk

        getStartSetting(id, token).expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("A")
            .jsonPath("$.openingScene").isEqualTo("O2")
    }

    @Test
    fun `시작설정이 없으면 조회는 빈 값이다`() {
        val owner = saveUser()
        val id = seedStory(owner.id).publicId.toString()

        getStartSetting(id, tokenFor(owner)).expectStatus().isOk
            .expectBody().jsonPath("$.prologue").isEmpty
    }

    @Test
    fun `없는 스토리의 시작설정 저장·조회는 404다`() {
        val token = tokenFor(saveUser())
        putStartSetting(UUID.randomUUID().toString(), """{"name":"x"}""", token).expectStatus().isNotFound
        getStartSetting(UUID.randomUUID().toString(), token).expectStatus().isNotFound
    }
}
