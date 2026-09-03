package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryLike
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryLikeRepository
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
 * 공개 스토리 목록 조회 통합 검증(KNK-149).
 * - 비로그인으로 200이고 공개(PUBLISHED∧PUBLIC)·미삭제·회원 소유 스토리만 나온다.
 * - 게스트 소유(user_id NULL)는 공개 범위를 고를 수 없어 기본값 PUBLIC으로 생긴 체험 스토리라 제외한다.
 * - latest는 createdAt 내림차순, popular는 좋아요 수 내림차순이고 동률은 publicId 내림차순으로 결정적이다.
 * - 커서는 keyset이라 페이지 사이에 중복·누락이 없고, 정렬이 다른 커서는 400이다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicStoryListControllerIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyLikeRepository: StoryLikeRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(nickname: String = "작가"): User = userRepository.save(User(nickname = nickname))

    private fun saveStory(
        owner: User?,
        title: String,
        createdAt: Instant = Instant.parse("2026-06-01T00:00:00Z"),
        status: StoryStatus = StoryStatus.PUBLISHED,
        visibility: StoryVisibility = StoryVisibility.PUBLIC,
        deletedAt: Instant? = null,
    ): Story = storyRepository.save(
        Story(
            userId = owner?.id,
            title = title,
            oneLineIntro = "$title 소개",
            genre = "판타지",
            createdAt = createdAt,
            status = status,
            visibility = visibility,
            deletedAt = deletedAt,
        ),
    )

    private fun like(story: Story, times: Int) {
        repeat(times) { storyLikeRepository.save(StoryLike(userId = saveUser("좋아요$it-${story.id}").id, storyId = story.id)) }
    }

    private fun get(query: String) =
        restTestClient.get().uri("$PATH$query").exchange()

    @Test
    fun `비로그인으로 공개 회원 스토리만 최신순으로 반환한다`() {
        val author = saveUser()
        val older = saveStory(author, "먼저", createdAt = Instant.parse("2026-06-01T00:00:00Z"))
        val newer = saveStory(author, "나중", createdAt = Instant.parse("2026-06-02T00:00:00Z"))

        get("")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(2)
            .jsonPath("$.items[0].id").isEqualTo(newer.publicId.toString())
            .jsonPath("$.items[1].id").isEqualTo(older.publicId.toString())
            .jsonPath("$.nextCursor").doesNotExist()
    }

    @Test
    fun `비공개 초안 삭제 게스트 소유는 목록에서 제외한다`() {
        val author = saveUser()
        val visible = saveStory(author, "공개")
        saveStory(author, "비공개", visibility = StoryVisibility.PRIVATE)
        saveStory(author, "초안", status = StoryStatus.DRAFT)
        saveStory(author, "삭제됨", deletedAt = Instant.now())
        saveStory(null, "게스트 제작")

        get("")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.items[0].id").isEqualTo(visible.publicId.toString())
    }

    @Test
    fun `latest 커서로 다음 페이지를 이어 읽고 중복도 누락도 없다`() {
        val author = saveUser()
        val stories = (1..3).map {
            saveStory(author, "스토리$it", createdAt = Instant.parse("2026-06-0${it}T00:00:00Z"))
        }
        val expectedOrder = stories.reversed().map { it.publicId.toString() }

        val firstPage = get("?limit=2").expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(2)
            .jsonPath("$.items[0].id").isEqualTo(expectedOrder[0])
            .jsonPath("$.items[1].id").isEqualTo(expectedOrder[1])
            .jsonPath("$.nextCursor").exists()
            .returnResult()
        val cursor = cursorOf(firstPage.responseBody)

        get("?limit=2&cursor=$cursor")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.items[0].id").isEqualTo(expectedOrder[2])
            .jsonPath("$.nextCursor").doesNotExist()
    }

    @Test
    fun `popular는 좋아요 수 내림차순이고 동률은 publicId 내림차순이다`() {
        val author = saveUser()
        val hot = saveStory(author, "인기")
        val mild = saveStory(author, "보통")
        val tiedA = saveStory(author, "동률A")
        val tiedB = saveStory(author, "동률B")
        like(hot, 3)
        like(mild, 1)

        // DB는 UUID를 바이트 **부호 없이** 비교하고 Java의 UUID.compareTo는 long을 부호 있게 비교한다.
        // 둘의 순서가 갈리므로 기대값도 DB와 같은 정렬(정규 표기 문자열 = 부호 없는 바이트 순)로 만든다.
        val tiedExpected = listOf(tiedA, tiedB).map { it.publicId.toString() }.sortedDescending()

        get("?sort=popular")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(4)
            .jsonPath("$.items[0].id").isEqualTo(hot.publicId.toString())
            .jsonPath("$.items[0].likeCount").isEqualTo(3)
            .jsonPath("$.items[1].id").isEqualTo(mild.publicId.toString())
            .jsonPath("$.items[2].id").isEqualTo(tiedExpected[0])
            .jsonPath("$.items[3].id").isEqualTo(tiedExpected[1])
    }

    @Test
    fun `popular도 커서로 이어 읽는다`() {
        val author = saveUser()
        val hot = saveStory(author, "인기")
        val mild = saveStory(author, "보통")
        val cold = saveStory(author, "무관심")
        like(hot, 3)
        like(mild, 1)

        val firstPage = get("?sort=popular&limit=2").expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].id").isEqualTo(hot.publicId.toString())
            .jsonPath("$.items[1].id").isEqualTo(mild.publicId.toString())
            .returnResult()

        get("?sort=popular&limit=2&cursor=${cursorOf(firstPage.responseBody)}")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.items[0].id").isEqualTo(cold.publicId.toString())
            .jsonPath("$.nextCursor").doesNotExist()
    }

    @Test
    fun `다른 정렬의 커서는 400이다`() {
        val author = saveUser()
        saveStory(author, "하나")
        saveStory(author, "둘")

        val latestCursor = cursorOf(
            get("?limit=1").expectStatus().isOk.expectBody().returnResult().responseBody,
        )

        get("?sort=popular&limit=1&cursor=$latestCursor").expectStatus().isBadRequest
    }

    @Test
    fun `망가진 커서는 400이다`() {
        get("?cursor=not-a-cursor").expectStatus().isBadRequest
    }

    @Test
    fun `알 수 없는 sort는 400이다`() {
        get("?sort=trending").expectStatus().isBadRequest
    }

    @Test
    fun `limit은 1에서 50 사이로 보정한다`() {
        val author = saveUser()
        (1..3).forEach { saveStory(author, "스토리$it", createdAt = Instant.parse("2026-06-0${it}T00:00:00Z")) }

        // 0 이하는 1로 올린다.
        get("?limit=0").expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(1)
        // 상한을 넘으면 50으로 내린다(심은 3건이 모두 나온다).
        get("?limit=100").expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(3)
    }

    @Test
    fun `숫자가 아닌 limit은 400이다`() {
        get("?limit=abc").expectStatus().isBadRequest
    }

    @Test
    fun `카드에 작성자 좋아요 수 장르가 실린다`() {
        val author = saveUser("김작가")
        val story = saveStory(author, "카드 확인")
        like(story, 2)

        get("")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].author.nickname").isEqualTo("김작가")
            .jsonPath("$.items[0].likeCount").isEqualTo(2)
            .jsonPath("$.items[0].turnCount").isEqualTo(0)
            .jsonPath("$.items[0].genres[0]").isEqualTo("판타지")
            .jsonPath("$.items[0].status").isEqualTo("PUBLISHED")
    }

    /** 응답 본문에서 nextCursor 값을 뽑는다(테스트 사이 값 전달용). */
    private fun cursorOf(body: ByteArray?): String =
        CURSOR_PATTERN.find(String(body ?: ByteArray(0)))?.groupValues?.get(1)
            ?: error("nextCursor가 응답에 없습니다: ${String(body ?: ByteArray(0))}")

    companion object {
        private const val PATH = "/api/v1/stories"
        private val CURSOR_PATTERN = """"nextCursor":"([^"]+)"""".toRegex()
    }
}
