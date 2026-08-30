package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryReportReason
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryReportRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
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
 * 스토리 신고(스펙 §4-3-1 스토리 신고, KNK-1020).
 * 등록 201·멱등(중복 신고 흡수), 인증 필수 401, 읽기 가시성 404, 검증 400, 정지 403.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryReportControllerIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyReportRepository: StoryReportRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveMember(status: UserStatus = UserStatus.ACTIVE): User =
        userRepository.save(User(nickname = "신고자", status = status))

    private fun savePublicStory(): Story =
        storyRepository.save(
            Story(title = "신고 대상 스토리", visibility = StoryVisibility.PUBLIC, status = StoryStatus.PUBLISHED),
        )

    private fun tokenFor(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    @Test
    fun `회원은 공개 스토리를 사유와 상세로 신고할 수 있다`() {
        val reporter = saveMember()
        val story = savePublicStory()

        restTestClient.post().uri("/api/v1/stories/${story.publicId}/reports")
            .header("Authorization", "Bearer ${tokenFor(reporter)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"reason":"SPAM","detail":"도배성 홍보 스토리입니다."}""")
            .exchange()
            .expectStatus().isCreated

        val saved = storyReportRepository.findAll().single()
        assertEquals(reporter.id, saved.userId)
        assertEquals(story.id, saved.storyId)
        assertEquals(StoryReportReason.SPAM, saved.reason)
        assertEquals("도배성 홍보 스토리입니다.", saved.detail)
    }

    @Test
    fun `같은 스토리 재신고는 멱등하게 201이고 행이 늘지 않는다`() {
        val reporter = saveMember()
        val story = savePublicStory()
        val token = tokenFor(reporter)

        repeat(2) {
            restTestClient.post().uri("/api/v1/stories/${story.publicId}/reports")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"reason":"INAPPROPRIATE"}""")
                .exchange()
                .expectStatus().isCreated
        }

        assertEquals(1, storyReportRepository.count())
    }

    @Test
    fun `미인증 신고는 401이다`() {
        val story = savePublicStory()

        restTestClient.post().uri("/api/v1/stories/${story.publicId}/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"reason":"SPAM"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `읽을 수 없는 스토리 신고는 404다(존재 비노출)`() {
        val owner = saveMember()
        val reporter = userRepository.save(User(nickname = "타인", status = UserStatus.ACTIVE))
        val privateStory = storyRepository.save(
            Story(title = "비공개", userId = owner.id, visibility = StoryVisibility.PRIVATE, status = StoryStatus.PUBLISHED),
        )

        restTestClient.post().uri("/api/v1/stories/${privateStory.publicId}/reports")
            .header("Authorization", "Bearer ${tokenFor(reporter)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"reason":"SPAM"}""")
            .exchange()
            .expectStatus().isNotFound

        assertEquals(0, storyReportRepository.count())
    }

    @Test
    fun `알 수 없는 사유나 상한 초과 상세는 400이다`() {
        val reporter = saveMember()
        val story = savePublicStory()
        val token = tokenFor(reporter)

        restTestClient.post().uri("/api/v1/stories/${story.publicId}/reports")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"reason":"NOT_A_REASON"}""")
            .exchange()
            .expectStatus().isBadRequest

        restTestClient.post().uri("/api/v1/stories/${story.publicId}/reports")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"reason":"ETC","detail":"${"가".repeat(501)}"}""")
            .exchange()
            .expectStatus().isBadRequest

        assertEquals(0, storyReportRepository.count())
    }

    @Test
    fun `정지된 회원의 신고는 403이다`() {
        val suspended = saveMember(status = UserStatus.SUSPENDED)
        val story = savePublicStory()

        restTestClient.post().uri("/api/v1/stories/${story.publicId}/reports")
            .header("Authorization", "Bearer ${tokenFor(suspended)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"reason":"SPAM"}""")
            .exchange()
            .expectStatus().isForbidden

        assertEquals(0, storyReportRepository.count())
    }
}
