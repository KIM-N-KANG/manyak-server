package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant
import java.util.UUID

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.official-user-public-id=11111111-2222-3333-4444-555555555555"],
)
class StoryOriginalsControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveOfficialUser(): User =
        userRepository.save(
            User(
                publicId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
                nickname = "마냑",
            ),
        )

    @Test
    fun `공식 계정의 공개 스토리만 등록순으로 반환하고 author에 닉네임을 싣는다`() {
        val official = saveOfficialUser()
        val other = userRepository.save(User(nickname = "다른유저"))

        val first = storyRepository.save(
            Story(
                userId = official.id,
                title = "유운잔검기",
                oneLineIntro = "복수 무협",
                genre = "무협",
                createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            ),
        )
        val second = storyRepository.save(
            Story(
                userId = official.id,
                title = "0호선",
                oneLineIntro = "지하철 괴담",
                genre = "호러",
                createdAt = Instant.parse("2026-08-02T00:00:00Z"),
            ),
        )
        // 비공개·초안·삭제·타인 소유는 노출되지 않아야 한다.
        storyRepository.save(
            Story(userId = official.id, title = "비공개", visibility = StoryVisibility.PRIVATE),
        )
        storyRepository.save(
            Story(userId = official.id, title = "초안", status = StoryStatus.DRAFT),
        )
        storyRepository.save(
            Story(userId = official.id, title = "삭제됨", deletedAt = Instant.now()),
        )
        storyRepository.save(
            Story(userId = other.id, title = "타인 공개 스토리"),
        )

        restTestClient.get()
            .uri("/api/v1/stories/originals")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].id").isEqualTo(first.publicId.toString())
            .jsonPath("$[0].title").isEqualTo("유운잔검기")
            .jsonPath("$[0].author.nickname").isEqualTo("마냑")
            .jsonPath("$[0].status").isEqualTo("PUBLISHED")
            .jsonPath("$[1].id").isEqualTo(second.publicId.toString())
            .jsonPath("$[1].author.nickname").isEqualTo("마냑")
    }

    @Test
    fun `공식 계정 스토리가 없으면 빈 목록을 반환한다`() {
        saveOfficialUser()

        restTestClient.get()
            .uri("/api/v1/stories/originals")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(0)
    }
}

/** 공식 계정 설정이 비어 있는 환경(로컬 기본값)에서는 조용히 빈 목록을 반환한다. */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryOriginalsUnconfiguredIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `공식 계정 설정이 없으면 빈 목록을 반환한다`() {
        restTestClient.get()
            .uri("/api/v1/stories/originals")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(0)
    }
}
