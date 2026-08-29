package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 스토리 좋아요 API(KNK-1017, 스펙 §4-3-1 스토리 좋아요) 통합 테스트.
 *
 * 계약: like만(dislike 없음), 등록 POST·취소 DELETE 모두 204·멱등, 인증 필수(401),
 * 대상 스토리에 읽기 가시성 게이트([Story.isReadableBy]) 적용(읽을 수 없으면 404).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryLikeControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `좋아요 등록은 204고 재등록도 204로 멱등하며 likeCount는 1을 넘지 않는다`() {
        val user = userRepository.save(User(nickname = "좋아요회원", status = UserStatus.ACTIVE))
        val story = publicStory()
        val token = jwtTokenProvider.issueAccessToken(user.publicId)

        repeat(2) {
            restTestClient.post()
                .uri("/api/v1/stories/${story.publicId}/like")
                .header("Authorization", "Bearer $token")
                .exchange()
                .expectStatus().isNoContent
        }

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.likeCount").isEqualTo(1)
            .jsonPath("$.isLiked").isEqualTo(true)
    }

    @Test
    fun `좋아요 취소는 204고 좋아요가 없는 스토리의 취소도 204로 멱등하다`() {
        val user = userRepository.save(User(nickname = "취소회원", status = UserStatus.ACTIVE))
        val story = publicStory()
        val token = jwtTokenProvider.issueAccessToken(user.publicId)

        restTestClient.post()
            .uri("/api/v1/stories/${story.publicId}/like")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isNoContent

        // 첫 취소는 실제 삭제, 두 번째 취소는 지울 행이 없지만 같은 204다.
        repeat(2) {
            restTestClient.delete()
                .uri("/api/v1/stories/${story.publicId}/like")
                .header("Authorization", "Bearer $token")
                .exchange()
                .expectStatus().isNoContent
        }

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.likeCount").isEqualTo(0)
            .jsonPath("$.isLiked").isEqualTo(false)
    }

    @Test
    fun `미인증 좋아요 등록·취소는 401이다`() {
        val story = publicStory()

        restTestClient.post()
            .uri("/api/v1/stories/${story.publicId}/like")
            .exchange()
            .expectStatus().isUnauthorized

        restTestClient.delete()
            .uri("/api/v1/stories/${story.publicId}/like")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `읽을 수 없는 타인의 비공개 스토리는 좋아요 등록·취소 모두 404다`() {
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        val other = userRepository.save(User(nickname = "타인", status = UserStatus.ACTIVE))
        val story = storyRepository.save(
            Story(
                title = "비공개 스토리",
                userId = owner.id,
                visibility = StoryVisibility.PRIVATE,
                status = StoryStatus.PUBLISHED,
            ),
        )
        val token = jwtTokenProvider.issueAccessToken(other.publicId)

        restTestClient.post()
            .uri("/api/v1/stories/${story.publicId}/like")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isNotFound

        restTestClient.delete()
            .uri("/api/v1/stories/${story.publicId}/like")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `존재하지 않는 스토리 좋아요는 404다`() {
        val user = userRepository.save(User(nickname = "회원", status = UserStatus.ACTIVE))
        val token = jwtTokenProvider.issueAccessToken(user.publicId)

        restTestClient.post()
            .uri("/api/v1/stories/${UUID.randomUUID()}/like")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isNotFound

        // 순차 정수 등 UUID가 아닌 값도 존재 여부를 노출하지 않고 동일한 404다.
        restTestClient.post()
            .uri("/api/v1/stories/1/like")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `상세 isLiked는 좋아요한 회원만 true고 타인·게스트는 false다`() {
        val liker = userRepository.save(User(nickname = "누른회원", status = UserStatus.ACTIVE))
        val other = userRepository.save(User(nickname = "안누른회원", status = UserStatus.ACTIVE))
        val story = publicStory()

        restTestClient.post()
            .uri("/api/v1/stories/${story.publicId}/like")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(liker.publicId)}")
            .exchange()
            .expectStatus().isNoContent

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(other.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isLiked").isEqualTo(false)
            // 좋아요 수는 누가 보든 같은 실 집계다.
            .jsonPath("$.likeCount").isEqualTo(1)

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isLiked").isEqualTo(false)
            .jsonPath("$.likeCount").isEqualTo(1)
    }

    @Test
    fun `목록 likeCount는 스토리별 실 집계이며 좋아요가 없는 스토리는 0이다`() {
        val first = userRepository.save(User(nickname = "회원1", status = UserStatus.ACTIVE))
        val second = userRepository.save(User(nickname = "회원2", status = UserStatus.ACTIVE))
        val liked = publicStory("좋아요 있는 스토리")
        val unliked = publicStory("좋아요 없는 스토리")

        listOf(first, second).forEach {
            restTestClient.post()
                .uri("/api/v1/stories/${liked.publicId}/like")
                .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(it.publicId)}")
                .exchange()
                .expectStatus().isNoContent
        }

        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["${liked.publicId}","${unliked.publicId}"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].likeCount").isEqualTo(2)
            .jsonPath("$[1].likeCount").isEqualTo(0)
    }

    private fun publicStory(title: String = "공개 스토리"): Story =
        storyRepository.save(
            Story(
                title = title,
                visibility = StoryVisibility.PUBLIC,
                status = StoryStatus.PUBLISHED,
            ),
        )
}
