package com.knk.manyak.migration.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
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
 * POST /api/v1/auth/migrate 통합 검증(게스트 데이터 마이그레이션).
 *
 * - 인증 필수: 토큰 없음·사용자 없음 → 401.
 * - 항목별 status(MIGRATED/ALREADY_OWNED/CONFLICT/NOT_FOUND)와 실제 소유권(user_id) 반영을 확인한다.
 * - 이관은 계정당 1회(≥1건 MIGRATED 시 잠금): 이후 호출은 평가 없이 migrationClosed=true로 닫힌다(스펙 §4-3-5).
 * - 형식 오류·상한 초과 → 400.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MigrationControllerIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var storyCreationSessionRepository: StoryCreationSessionRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun saveStory(ownerId: Long?): Story =
        storyRepository.save(Story(userId = ownerId, title = "제목"))

    private fun saveChat(ownerId: Long?): StoryChat {
        // 채팅은 실제 스토리를 참조하도록 픽스처를 만든다(story_id 무결성 유지).
        val story = storyRepository.save(Story(title = "채팅용 스토리"))
        return storyChatRepository.save(StoryChat(userId = ownerId, storyId = story.id))
    }

    private fun accessTokenFor(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    // ---- 인증 ----

    @Test
    fun `토큰 없이 migrate를 호출하면 401이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":[],"chatIds":[]}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `유효한 토큰이지만 사용자가 없으면 401이다`() {
        val token = jwtTokenProvider.issueAccessToken(UUID.randomUUID())

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":[],"chatIds":[]}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    // ---- 정지 계정(§4-5 B20, KNK-499) ----

    @Test
    fun `정지된 회원은 이관 요청이 403이고 평가되지 않는다`() {
        val suspended = userRepository.save(User(nickname = "정지회원", status = UserStatus.SUSPENDED))
        val guestStory = saveStory(ownerId = null)

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(suspended)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["${guestStory.publicId}"],"chatIds":[]}""")
            .exchange()
            .expectStatus().isForbidden

        assertThat(storyRepository.findById(guestStory.id).orElseThrow().userId).isNull()
    }

    // ---- 소유권 판정 ----

    @Test
    fun `스토리 소유권을 상태별로 판정하고 게스트 스토리를 요청자에게 이관한다`() {
        val me = saveUser("me")
        val other = saveUser("other")
        val guest = saveStory(ownerId = null)
        val mine = saveStory(ownerId = me.id)
        val others = saveStory(ownerId = other.id)
        val missing = UUID.randomUUID()

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"storyIds":["${guest.publicId}","${mine.publicId}","${others.publicId}","$missing"],"chatIds":[]}""",
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.stories[0].status").isEqualTo("MIGRATED")
            .jsonPath("$.stories[1].status").isEqualTo("ALREADY_OWNED")
            .jsonPath("$.stories[2].status").isEqualTo("CONFLICT")
            .jsonPath("$.stories[3].status").isEqualTo("NOT_FOUND")

        // 실제 소유권: 게스트 스토리는 요청자에게, 타인 소유는 그대로.
        assertThat(storyRepository.findByPublicIdAndDeletedAtIsNull(guest.publicId)!!.userId).isEqualTo(me.id)
        assertThat(storyRepository.findByPublicIdAndDeletedAtIsNull(others.publicId)!!.userId).isEqualTo(other.id)
    }

    @Test
    fun `게스트 채팅도 독립적으로 이관된다`() {
        val me = saveUser("me")
        val guestChat = saveChat(ownerId = null)

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":[],"chatIds":["${guestChat.publicId}"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.chats[0].status").isEqualTo("MIGRATED")

        assertThat(storyChatRepository.findByPublicIdAndDeletedAtIsNull(guestChat.publicId)!!.userId).isEqualTo(me.id)
    }

    @Test
    fun `스토리를 이관하면 연결된 생성 세션 소유권도 함께 클레임한다`() {
        val me = saveUser("me")
        val guest = saveStory(ownerId = null)
        val session = storyCreationSessionRepository.save(
            StoryCreationSession(userId = null, storyId = guest.id, status = StoryCreationSessionStatus.STORY_CREATED),
        )

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["${guest.publicId}"],"chatIds":[]}""")
            .exchange()
            .expectStatus().isOk

        assertThat(storyRepository.findByPublicIdAndDeletedAtIsNull(guest.publicId)!!.userId).isEqualTo(me.id)
        // 스토리와 함께 연결 세션도 요청자 소유로 바뀌어야 storyline 평가가 익명으로 열리지 않는다.
        assertThat(storyCreationSessionRepository.findById(session.id).orElseThrow().userId).isEqualTo(me.id)
    }

    @Test
    fun `이관에 성공하면 계정이 잠겨 2회차 호출은 평가 없이 migrationClosed로 닫힌다`() {
        val me = saveUser("me")
        val guest = saveStory(ownerId = null)

        // 1회차: MIGRATED, 아직 닫히지 않음
        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["${guest.publicId}"],"chatIds":[]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.stories[0].status").isEqualTo("MIGRATED")
            .jsonPath("$.migrationClosed").isEqualTo(false)

        // 계정이 잠긴 뒤 새 게스트 스토리를 제출해도(2회차) 평가 없이 닫히고 빈 결과를 준다.
        val secondGuest = saveStory(ownerId = null)
        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["${secondGuest.publicId}"],"chatIds":[]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.migrationClosed").isEqualTo(true)
            .jsonPath("$.stories").isEmpty
            .jsonPath("$.chats").isEmpty

        // 1회차 스토리는 요청자 소유로 이관됐고, 2회차 스토리는 잠금으로 평가되지 않아 게스트(NULL)로 남는다.
        assertThat(storyRepository.findByPublicIdAndDeletedAtIsNull(guest.publicId)!!.userId).isEqualTo(me.id)
        assertThat(storyRepository.findByPublicIdAndDeletedAtIsNull(secondGuest.publicId)!!.userId).isNull()
        // 이관 완료 시각이 기록돼 계정이 잠긴다.
        assertThat(userRepository.findById(me.id).orElseThrow().migratedAt).isNotNull()
    }

    // ---- 검증 오류 ----

    @Test
    fun `공개 ID가 UUID 형식이 아니면 400이다`() {
        val me = saveUser("me")

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["not-a-uuid"],"chatIds":[]}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `배열이 100개를 넘으면 400이다`() {
        val me = saveUser("me")
        val ids = (1..101).joinToString(",") { "\"${UUID.randomUUID()}\"" }

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":[$ids],"chatIds":[]}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `빈 배열이면 200과 빈 결과다`() {
        val me = saveUser("me")

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":[],"chatIds":[]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.stories").isEmpty
            .jsonPath("$.chats").isEmpty
    }
}
