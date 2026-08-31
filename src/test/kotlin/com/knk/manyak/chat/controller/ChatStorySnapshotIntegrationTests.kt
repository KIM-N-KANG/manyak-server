package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.dto.CreditTransactionPageResponse
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant

/**
 * 채팅 스토리 제목·썸네일 스냅샷 검증(KNK-1059, PR #216 Codex P1).
 *
 * 규칙: `story.isReadableBy(userId)`이고 삭제되지 않았으면 스토리의 **현재** 값, 아니면 채팅에 박아둔 **스냅샷**.
 * 공개 스토리로 채팅한 뒤 소유자가 비공개로 되돌리고 제목을 바꾸면, 그 뒤 값이 남에게 계속 흘러가면 안 된다.
 * 서재(`GET /users/me/chats`)와 이용내역(`GET /users/me/credits/transactions`) 양쪽을 같은 규칙으로 고정한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["manyak.asset.image-base-url=https://cdn.test"])
class ChatStorySnapshotIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var transactionRepository: CreditTransactionRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun token(user: User) = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    /** 소유자 A의 공개 스토리. 제목·썸네일 모두 나중에 바꿔칠 값이다. */
    private fun publicStory(owner: User): Story =
        storyRepository.save(
            Story(userId = owner.id, title = "원래 제목", thumbnailImageKey = "thumb_0001"),
        )

    /** 실제 생성 API로 채팅을 만든다 — 스냅샷이 그 경로에서 박히는지까지 함께 검증하기 위해서다. */
    private fun createChat(story: Story, user: User): StoryChat {
        restTestClient.post()
            .uri("/api/v1/chats")
            .header("Authorization", token(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isCreated
        return storyChatRepository.findAll().first { it.userId == user.id && it.storyId == story.id }
    }

    /** 소유자가 스토리를 비공개(초안)로 되돌리고 제목을 바꾼다. */
    private fun hideAndRename(story: Story, title: String) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.status = StoryStatus.DRAFT
        loaded.visibility = StoryVisibility.PRIVATE
        loaded.title = title
        storyRepository.save(loaded)
    }

    /**
     * 비공개로 되돌린 뒤 썸네일 키까지 갈아끼운다.
     *
     * `Story.thumbnailImageKey`는 등록 시 1회 확정되는 `val`이라 앱에서 바꿀 경로가 없다(스펙 §4-3-9).
     * 그래서 컬럼을 직접 갱신해 "스토리 쪽 값이 달라진 상태"를 만든다 — 응답이 스토리 컬럼이 아니라
     * 채팅 스냅샷을 읽고 있는지 가리는 게 목적이라, 값이 달라지기만 하면 검증은 성립한다.
     */
    private fun hideStoryAndSwapThumbnail(story: Story, thumbnailKey: String) {
        hideAndRename(story, "바뀐 제목")
        jdbcTemplate.update("UPDATE stories SET thumbnail_image_key = ? WHERE id = ?", thumbnailKey, story.id)
    }

    private fun republish(story: Story) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.status = StoryStatus.PUBLISHED
        loaded.visibility = StoryVisibility.PUBLIC
        storyRepository.save(loaded)
    }

    /** 공개 상태를 유지한 채 제목만 바꾼다(삭제 케이스에서 현재 값과 스냅샷을 구분하기 위해). */
    private fun rename(story: Story, title: String) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.title = title
        storyRepository.save(loaded)
    }

    private fun softDelete(story: Story) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.deletedAt = Instant.now()
        storyRepository.save(loaded)
    }

    /** 이 채팅을 참조하는 크레딧 소모 행. 이용내역이 제목을 붙이는 대상이다. */
    private fun chargeFor(chat: StoryChat, userId: Long) {
        transactionRepository.save(
            CreditTransaction(
                userId = userId,
                amount = -10,
                reason = CreditReason.CHAT_TURN,
                refType = "CHAT",
                refId = chat.id,
            ),
        )
    }

    private fun libraryCard(user: User): ChatSummaryResponse =
        restTestClient.get()
            .uri("/api/v1/users/me/chats")
            .header("Authorization", token(user))
            .exchange()
            .expectStatus().isOk
            .expectBody(Array<ChatSummaryResponse>::class.java)
            .returnResult().responseBody!!.first()

    private fun historyTitle(user: User): String? =
        restTestClient.get()
            .uri("/api/v1/users/me/credits/transactions")
            .header("Authorization", token(user))
            .exchange()
            .expectStatus().isOk
            .expectBody(CreditTransactionPageResponse::class.java)
            .returnResult().responseBody!!.items.first().title

    // ---- 스냅샷 기록 ----

    @Test
    fun `채팅을 만들면 스토리 제목과 썸네일이 스냅샷으로 박힌다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)

        val chat = createChat(story, reader)

        assertThat(chat.storyTitleSnapshot).isEqualTo("원래 제목")
        assertThat(chat.storyThumbnailKeySnapshot).isEqualTo("thumb_0001")
    }

    // ---- 서재 ----

    @Test
    fun `비공개로 되돌리고 제목을 바꿔도 서재는 스냅샷 제목을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        createChat(story, reader)

        hideAndRename(story, "바뀐 제목")

        assertThat(libraryCard(reader).storyTitle).isEqualTo("원래 제목")
    }

    @Test
    fun `다시 공개하면 서재가 현재 제목으로 복귀한다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        createChat(story, reader)

        hideAndRename(story, "바뀐 제목")
        republish(story)

        assertThat(libraryCard(reader).storyTitle).isEqualTo("바뀐 제목")
    }

    @Test
    fun `스토리가 삭제되면 서재는 스냅샷 제목을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        createChat(story, reader)

        // 삭제는 isReadableBy가 보지 않는 조건이라 호출부가 따로 판정해야 한다.
        // 지우기 전에 제목을 바꿔둬야 "현재 값을 그대로 읽어도 통과"하는 무의미한 검증이 되지 않는다.
        rename(story, "바뀐 제목")
        softDelete(story)

        assertThat(libraryCard(reader).storyTitle).isEqualTo("원래 제목")
    }

    @Test
    fun `소유자 본인은 비공개 스토리도 서재에서 현재 제목으로 본다`() {
        val owner = saveUser("소유자")
        val story = publicStory(owner)
        createChat(story, owner)

        hideAndRename(story, "바뀐 제목")

        assertThat(libraryCard(owner).storyTitle).isEqualTo("바뀐 제목")
    }

    @Test
    fun `비공개로 되돌린 뒤 썸네일이 바뀌어도 서재는 스냅샷 썸네일 URL을 준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        createChat(story, reader)

        hideStoryAndSwapThumbnail(story, "thumb_9999")

        assertThat(libraryCard(reader).thumbnailUrlSm).isEqualTo("https://cdn.test/thumbnails/thumb_0001_sm.png")
    }

    // ---- 이용내역 ----

    @Test
    fun `비공개로 되돌리고 제목을 바꿔도 이용내역은 스냅샷 제목을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        val chat = createChat(story, reader)
        chargeFor(chat, reader.id)

        hideAndRename(story, "바뀐 제목")

        assertThat(historyTitle(reader)).isEqualTo("원래 제목")
    }

    @Test
    fun `다시 공개하면 이용내역이 현재 제목으로 복귀한다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        val chat = createChat(story, reader)
        chargeFor(chat, reader.id)

        hideAndRename(story, "바뀐 제목")
        republish(story)

        assertThat(historyTitle(reader)).isEqualTo("바뀐 제목")
    }

    @Test
    fun `스토리가 삭제되면 이용내역은 스냅샷 제목을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        val chat = createChat(story, reader)
        chargeFor(chat, reader.id)

        rename(story, "바뀐 제목")
        softDelete(story)

        assertThat(historyTitle(reader)).isEqualTo("원래 제목")
    }

    @Test
    fun `소유자 본인은 비공개 스토리도 이용내역에서 현재 제목으로 본다`() {
        val owner = saveUser("소유자")
        val story = publicStory(owner)
        val chat = createChat(story, owner)
        chargeFor(chat, owner.id)

        hideAndRename(story, "바뀐 제목")

        assertThat(historyTitle(owner)).isEqualTo("바뀐 제목")
    }
}
