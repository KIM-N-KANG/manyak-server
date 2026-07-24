package com.knk.manyak.user.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant

/**
 * GET /api/v1/users/me/stories · /chats 통합 검증(회원 서재, KNK-447).
 *
 * - 인증 필수: 토큰 없음·사용자 없음 → 401.
 * - 요청자 소유만, 소프트 삭제 제외, 정렬(스토리 생성 최신순 · 채팅 최근 활동순), limit 상한.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserContentControllerIntegrationTests {


    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun accessTokenFor(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    private val t1: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val t2: Instant = Instant.parse("2026-02-01T00:00:00Z")

    // ---- 인증 ----

    @Test
    fun `토큰 없이 내 스토리 목록을 호출하면 401이다`() {
        restTestClient.get().uri("/api/v1/users/me/stories").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `토큰 없이 내 채팅 목록을 호출하면 401이다`() {
        restTestClient.get().uri("/api/v1/users/me/chats").exchange().expectStatus().isUnauthorized
    }

    // ---- 내 스토리 목록 ----

    @Test
    fun `내 스토리 목록은 요청자 소유·미삭제만 생성 최신순으로 반환한다`() {
        val me = saveUser("me")
        val other = saveUser("other")
        storyRepository.save(Story(userId = me.id, title = "old", createdAt = t1))
        storyRepository.save(Story(userId = me.id, title = "new", createdAt = t2))
        storyRepository.save(Story(userId = other.id, title = "other"))
        storyRepository.save(Story(userId = null, title = "guest"))
        storyRepository.save(Story(userId = me.id, title = "deleted", createdAt = t2, deletedAt = Instant.now()))

        restTestClient.get()
            .uri("/api/v1/users/me/stories")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].title").isEqualTo("new")
            .jsonPath("$[1].title").isEqualTo("old")
    }

    @Test
    fun `내 스토리 목록은 limit으로 개수를 제한한다`() {
        val me = saveUser("me")
        repeat(3) { storyRepository.save(Story(userId = me.id, title = "s$it")) }

        restTestClient.get()
            .uri("/api/v1/users/me/stories?limit=2")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
    }

    // ---- 내 채팅 목록 ----

    @Test
    fun `내 채팅 목록은 요청자 소유·미삭제만 최근 활동순으로 반환한다`() {
        val me = saveUser("me")
        val other = saveUser("other")
        val story = storyRepository.save(Story(userId = me.id, title = "제목"))
        storyChatRepository.save(StoryChat(userId = me.id, storyId = story.id, updatedAt = t1))
        storyChatRepository.save(StoryChat(userId = me.id, storyId = story.id, updatedAt = t2))
        storyChatRepository.save(StoryChat(userId = other.id, storyId = story.id))
        storyChatRepository.save(StoryChat(userId = null, storyId = story.id))
        storyChatRepository.save(StoryChat(userId = me.id, storyId = story.id, updatedAt = t2, deletedAt = Instant.now()))

        restTestClient.get()
            .uri("/api/v1/users/me/chats")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            // 최근 활동순: t2(최신)가 먼저. storyTitle은 연결 스토리에서 채운다.
            .jsonPath("$[0].updatedAt").exists()
            .jsonPath("$[0].storyTitle").isEqualTo("제목")
            .jsonPath("$[1].updatedAt").exists()
    }

    @Test
    fun `유효한 토큰이지만 사용자가 없으면 401이다`() {
        val token = jwtTokenProvider.issueAccessToken(java.util.UUID.randomUUID())

        restTestClient.get()
            .uri("/api/v1/users/me/stories")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
