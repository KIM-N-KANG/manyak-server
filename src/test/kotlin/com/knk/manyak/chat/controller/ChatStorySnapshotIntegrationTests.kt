package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.dto.ChatDetailResponse
import com.knk.manyak.chat.dto.ChatShareResponse
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.dto.CreateChatShareResponse
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.credit.dto.CreditTransactionPageResponse
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.story.service.StoryPublicSnapshotService
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
 * 스토리 "마지막 공개 버전" 스냅샷의 읽기 경로 검증(KNK-1059 → KNK-1065).
 *
 * 규칙: `story.isCurrentMetadataVisibleTo(userId)`면 스토리의 **현재** 값, 아니면 그 스토리가 **마지막으로
 * 공개(PUBLISHED∧PUBLIC)였던 시점**의 스냅샷(`stories.last_public_snapshot`)이다. 공개 스토리로 채팅한 뒤
 * 소유자가 비공개로 되돌리고 고치면, 그 뒤 값이 남에게 계속 흘러가면 안 된다.
 *
 * 스냅샷이 채팅이 아니라 스토리에 있으므로 **공개 상태에서 이뤄진 밸런스 패치는 반영되고**, 비공개 전환
 * 이후의 개작만 멈춘다(KNK-1059의 채팅별 스냅샷은 채팅 생성 시점으로 되돌려 보여줬다).
 *
 * 서재(`GET /users/me/chats`)·이용내역(`GET /users/me/credits/transactions`)·채팅 상세·공유 열람 넷을 같은 규칙으로 고정한다.
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
    @Autowired private lateinit var startSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var suggestedInputRepository: StorySuggestedInputRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var messageRepository: StoryMessageRepository
    @Autowired private lateinit var endingRepository: StoryEndingRepository
    @Autowired private lateinit var transactionRepository: CreditTransactionRepository
    @Autowired private lateinit var snapshotService: StoryPublicSnapshotService
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun token(user: User) = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    /**
     * 공개 상태 저장 = 마지막 공개 버전 스냅샷 갱신(KNK-1065). 실제로는 제작 등록·수정 API가 이 지점을 탄다
     * ([com.knk.manyak.story.controller.StoryEditIntegrationTests]가 그 배선을 고정한다).
     */
    private fun publish(story: Story) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        snapshotService.refresh(loaded)
        storyRepository.save(loaded)
    }

    /** 소유자 A의 공개 스토리. 제목·썸네일 모두 나중에 바꿔칠 값이다. */
    private fun publicStory(owner: User): Story =
        storyRepository.save(
            Story(userId = owner.id, title = "원래 제목", thumbnailImageKey = "thumb_0001"),
        ).also(::publish)

    /** 시작 설정(프롤로그)을 가진 공개 스토리. 프롤로그 스냅샷 검증에 쓴다. */
    private fun publicStoryWithPrologue(owner: User): Story {
        val story = publicStory(owner)
        startSettingRepository.save(
            StoryStartSetting(story = story, name = "시작 장면", prologue = "원래 프롤로그"),
        )
        publish(story)
        return story
    }

    /**
     * 엔딩에 도달한 상태를 만든다. 실제 도달(AI 판정 → ChatTurnPersister)은
     * [com.knk.manyak.chat.controller.ChatTurnEndingMainEventIntegrationTests]가 실 경로로 덮으므로,
     * 여기서는 읽기 규칙만 보려고 도달 결과를 직접 심는다.
     */
    private fun reachEnding(story: Story, chat: StoryChat, endingName: String): StoryEnding {
        val setting = startSettingRepository.findAll().first { it.story.id == story.id }
        val ending = endingRepository.save(
            StoryEnding(
                startSetting = setting,
                name = endingName,
                minTurns = 1,
                achievementCondition = "조건",
                epilogue = "에필로그",
                sortOrder = 1,
            ),
        )
        // 턴 1건(USER + 도달 표식이 붙은 ASSISTANT). 상세·공유의 turns[]가 이 메시지를 읽는다.
        messageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "마지막 일격", messageOrder = 1),
        )
        messageRepository.save(
            StoryMessage(
                chatId = chat.id,
                role = MessageRole.ASSISTANT,
                content = "이야기가 끝났다.",
                messageOrder = 2,
                reachedEndingId = ending.id,
            ),
        )
        val loaded = storyChatRepository.findById(chat.id).orElseThrow()
        loaded.currentTurn = 1
        loaded.reachedEndingId = ending.id
        storyChatRepository.save(loaded)
        // 엔딩은 스토리가 아직 공개일 때 추가됐으므로 스냅샷에도 담긴다.
        publish(story)
        return ending
    }

    /**
     * 엔딩 행이 삭제돼 참조가 끊긴 상태를 만든다(스토리 수정의 `endings[]` 전체 교체가 일으키는 상황).
     *
     * 운영에서는 `story_chats.reached_ending_id`·`story_messages.reached_ending_id`의 FK가
     * `ON DELETE SET NULL`이라(V41) 엔딩 행을 지우면 두 참조가 자동으로 NULL이 된다. 그런데 **테스트 스키마는
     * Flyway가 아니라 `ddl-auto`로 만들어져 그 삭제 동작이 없으므로**, 여기서는 FK가 만들어내는 결과 상태를
     * 직접 재현한다(참조 NULL + 엔딩 행 부재).
     */
    private fun breakEndingReference(chat: StoryChat, ending: StoryEnding) {
        jdbcTemplate.update("UPDATE story_chats SET reached_ending_id = NULL WHERE id = ?", chat.id)
        jdbcTemplate.update("UPDATE story_messages SET reached_ending_id = NULL WHERE chat_id = ?", chat.id)
        jdbcTemplate.update("DELETE FROM story_endings WHERE id = ?", ending.id)
    }

    /**
     * 소유자가 엔딩 이름을 바꾼다.
     *
     * `StoryEnding.name`은 `val`이고 수정 API의 `endings[]`는 전체 교체라 행이 새로 생긴다(채팅이 참조하던
     * `reached_ending_id`가 끊어진다). 응답이 엔딩 행이 아니라 채팅 스냅샷을 읽는지 가리는 게 목적이므로
     * 컬럼을 직접 갱신해 "엔딩 쪽 값만 달라진 상태"를 만든다.
     */
    private fun renameEnding(ending: StoryEnding, name: String) {
        jdbcTemplate.update("UPDATE story_endings SET name = ? WHERE id = ?", name, ending.id)
    }

    /** 시작 설정에 추천 입력을 심는다. `inputText`가 그대로 상세 응답에 실린다. */
    private fun seedSuggestedInput(story: Story, text: String) {
        val setting = startSettingRepository.findAll().first { it.story.id == story.id }
        suggestedInputRepository.deleteAll(suggestedInputRepository.findByStartSettingIdOrderByInputOrderAsc(setting.id))
        suggestedInputRepository.save(
            StorySuggestedInput(startSetting = setting, inputText = text, inputOrder = 1),
        )
    }

    /** 소유자가 **공개를 유지한 채** 프롤로그를 고친다(밸런스 패치). 스냅샷도 함께 갱신된다. */
    private fun patchPrologueWhilePublic(story: Story, prologue: String) {
        changePrologue(story, prologue)
        publish(story)
    }

    /** 소유자가 시작 설정의 프롤로그 본문을 고친다. */
    private fun changePrologue(story: Story, prologue: String) {
        val setting = startSettingRepository.findAll().first { it.story.id == story.id }
        setting.prologue = prologue
        startSettingRepository.save(setting)
    }

    /** 실제 생성 API로 채팅을 만든다. [startSettingId]를 주면 그 시작 설정으로 시작한다(KNK-515 복수화). */
    private fun createChat(story: Story, user: User, startSettingId: String? = null): StoryChat {
        val body = if (startSettingId == null) {
            """{"storyId":"${story.publicId}"}"""
        } else {
            """{"storyId":"${story.publicId}","startSettingId":"$startSettingId"}"""
        }
        restTestClient.post()
            .uri("/api/v1/chats")
            .header("Authorization", token(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
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
        publish(story)
    }

    /** 공개 상태를 유지한 채 제목만 바꾼다(밸런스 패치). 공개 저장이라 스냅샷도 이 값으로 옮겨간다. */
    private fun rename(story: Story, title: String) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.title = title
        storyRepository.save(loaded)
        publish(story)
    }

    private fun softDelete(story: Story) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.deletedAt = Instant.now()
        storyRepository.save(loaded)
    }

    /**
     * 스토리를 지운 뒤 제목을 바꾼다. 삭제 상태의 저장은 스냅샷을 갱신하지 않으므로, 응답이 현재 값을 읽고 있으면
     * 바로 드러난다. **공개 상태에서 바꾸면 그게 곧 마지막 공개 버전이라 두 값이 같아져 검증이 무의미해진다.**
     */
    private fun deleteAndRename(story: Story, title: String) {
        softDelete(story)
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.title = title
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

    private fun detailTitle(chat: StoryChat, user: User): String? =
        restTestClient.get()
            .uri("/api/v1/chats/${chat.publicId}")
            .header("Authorization", token(user))
            .exchange()
            .expectStatus().isOk
            .expectBody(ChatDetailResponse::class.java)
            .returnResult().responseBody!!.storyTitle

    /** 채팅 소유자가 공유 링크를 발급하고, 그 열람 토큰을 돌려준다. */
    private fun createShare(chat: StoryChat, user: User): String =
        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/shares")
            .header("Authorization", token(user))
            .exchange()
            .expectStatus().isCreated
            .expectBody(CreateChatShareResponse::class.java)
            .returnResult().responseBody!!.shareId

    /**
     * 공유 열람은 인증이 필요 없다 — [viewer]가 없으면 링크만 가진 익명 열람자를 그대로 재현한다.
     * 토큰을 실으면 그 요청자로 스토리 읽기 권한을 판정해야 한다.
     */
    private fun shareTitle(shareId: String, viewer: User? = null): String? =
        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .headers { headers -> viewer?.let { headers.set("Authorization", token(it)) } }
            .exchange()
            .expectStatus().isOk
            .expectBody(ChatShareResponse::class.java)
            .returnResult().responseBody!!.storyTitle

    private fun detailReachedEndings(chat: StoryChat, user: User): List<String?> =
        restTestClient.get()
            .uri("/api/v1/chats/${chat.publicId}")
            .header("Authorization", token(user))
            .exchange()
            .expectStatus().isOk
            .expectBody(ChatDetailResponse::class.java)
            .returnResult().responseBody!!.turns.map { it.reachedEnding }

    private fun shareReachedEndings(shareId: String, viewer: User? = null): List<String?> =
        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .headers { headers -> viewer?.let { headers.set("Authorization", token(it)) } }
            .exchange()
            .expectStatus().isOk
            .expectBody(ChatShareResponse::class.java)
            .returnResult().responseBody!!.turns.map { it.reachedEnding }

    private fun detailSuggestedInputs(chat: StoryChat, user: User): List<String> =
        restTestClient.get()
            .uri("/api/v1/chats/${chat.publicId}")
            .header("Authorization", token(user))
            .exchange()
            .expectStatus().isOk
            .expectBody(ChatDetailResponse::class.java)
            .returnResult().responseBody!!.suggestedInputs

    private fun detailPrologue(chat: StoryChat, user: User): String =
        restTestClient.get()
            .uri("/api/v1/chats/${chat.publicId}")
            .header("Authorization", token(user))
            .exchange()
            .expectStatus().isOk
            .expectBody(ChatDetailResponse::class.java)
            .returnResult().responseBody!!.prologue

    private fun sharePrologue(shareId: String, viewer: User? = null): String =
        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .headers { headers -> viewer?.let { headers.set("Authorization", token(it)) } }
            .exchange()
            .expectStatus().isOk
            .expectBody(ChatShareResponse::class.java)
            .returnResult().responseBody!!.prologue

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
    fun `공개 상태로 저장하면 스토리에 마지막 공개 버전 스냅샷이 박힌다`() {
        val owner = saveUser("소유자")
        val story = publicStory(owner)

        val snapshot = storyRepository.findById(story.id).orElseThrow().lastPublicSnapshot!!

        assertThat(snapshot.title).isEqualTo("원래 제목")
        assertThat(snapshot.thumbnailImageKey).isEqualTo("thumb_0001")
    }

    @Test
    fun `공개 상태에서 고친 제목은 비공개 전환 뒤에도 마지막 공개 버전으로 보인다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        createChat(story, reader)

        // 공개를 유지한 채 v2로 밸런스 패치 — 독자는 화면에서 이 값을 보고 있었다.
        rename(story, "v2 제목")
        // 그 뒤 감추고 개작한다.
        hideAndRename(story, "비공개 개작 제목")

        // 채팅 생성 시점(v1)이 아니라 마지막 공개 시점(v2)이어야 한다.
        assertThat(libraryCard(reader).storyTitle).isEqualTo("v2 제목")
    }

    @Test
    fun `스냅샷이 없는 스토리에서도 읽기 경로가 터지지 않는다`() {
        // 백필 대상 밖(백필 시점에 이미 비공개)인 스토리를 재현한다 — last_public_snapshot이 NULL이다.
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)
        chargeFor(chat, reader.id)
        storyRepository.save(storyRepository.findById(story.id).orElseThrow().also { it.lastPublicSnapshot = null })
        hideAndRename(story, "비공개 개작 제목")

        val shareId = createShare(chat, reader)

        assertThat(libraryCard(reader).storyTitle).isEmpty()
        assertThat(historyTitle(reader)).isNull()
        assertThat(detailTitle(chat, reader)).isEmpty()
        assertThat(shareTitle(shareId)).isEmpty()
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
        // 지운 뒤 제목을 바꿔둬야 "현재 값을 그대로 읽어도 통과"하는 무의미한 검증이 되지 않는다.
        deleteAndRename(story, "바뀐 제목")

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

        deleteAndRename(story, "바뀐 제목")

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

    // ---- 채팅 상세 ----

    @Test
    fun `비공개로 되돌리고 제목을 바꿔도 채팅 상세는 스냅샷 제목을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        val chat = createChat(story, reader)

        hideAndRename(story, "바뀐 제목")

        assertThat(detailTitle(chat, reader)).isEqualTo("원래 제목")
    }

    @Test
    fun `소유자 본인은 비공개 스토리도 채팅 상세에서 현재 제목으로 본다`() {
        val owner = saveUser("소유자")
        val story = publicStory(owner)
        val chat = createChat(story, owner)

        hideAndRename(story, "바뀐 제목")

        assertThat(detailTitle(chat, owner)).isEqualTo("바뀐 제목")
    }

    // ---- 공유 열람 ----

    @Test
    fun `비공개로 되돌리고 제목을 바꾸면 공유 열람은 스냅샷 제목을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        val chat = createChat(story, reader)
        val shareId = createShare(chat, reader)

        hideAndRename(story, "바뀐 제목")

        // 링크만 가진 익명 열람자에게 비공개로 되돌린 스토리의 최신 제목이 새면 안 된다.
        assertThat(shareTitle(shareId)).isEqualTo("원래 제목")
    }

    @Test
    fun `공개 스토리의 공유 열람은 현재 제목을 따라간다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        val chat = createChat(story, reader)
        val shareId = createShare(chat, reader)

        rename(story, "바뀐 제목")

        assertThat(shareTitle(shareId)).isEqualTo("바뀐 제목")
    }

    @Test
    fun `스토리가 삭제되면 공유 열람은 스냅샷 제목을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        val chat = createChat(story, reader)
        val shareId = createShare(chat, reader)

        deleteAndRename(story, "바뀐 제목")

        assertThat(shareTitle(shareId)).isEqualTo("원래 제목")
    }

    @Test
    fun `공유 열람에 토큰이 실리면 그 요청자로 읽기 권한을 판정한다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStory(owner)
        val chat = createChat(story, reader)
        val shareId = createShare(chat, reader)

        hideAndRename(story, "바뀐 제목")

        // 같은 링크라도 스토리 소유자가 열면 읽기가 허용되므로 현재 제목을 본다.
        assertThat(shareTitle(shareId, viewer = owner)).isEqualTo("바뀐 제목")
        // 익명 열람자는 그대로 스냅샷에서 멈춘다.
        assertThat(shareTitle(shareId)).isEqualTo("원래 제목")
    }

    // ---- 프롤로그 ----

    @Test
    fun `공개 상태로 저장하면 시작 설정의 프롤로그도 스냅샷에 담긴다`() {
        val owner = saveUser("소유자")
        val story = publicStoryWithPrologue(owner)
        val startSettingId = startSettingRepository.findAll().first { it.story.id == story.id }.id

        val snapshot = storyRepository.findById(story.id).orElseThrow().lastPublicSnapshot!!

        assertThat(snapshot.startSettingOf(startSettingId)?.prologue).isEqualTo("원래 프롤로그")
    }

    @Test
    fun `시작 설정이 여러 개면 그 채팅이 시작한 시작 설정의 프롤로그를 준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val second = startSettingRepository.save(
            StoryStartSetting(story = story, name = "두 번째 시작", prologue = "두 번째 프롤로그"),
        )
        publish(story)

        // 두 번째 시작 설정으로 채팅을 시작한 뒤 감춘다. 스냅샷은 시작 설정 id로 찾아야 하므로,
        // 첫 시작 설정을 집으면 남의 도입부가 나간다.
        val chat = createChat(story, reader, second.publicId.toString())
        hideAndRename(story, "바뀐 제목")
        changePrologue(story, "비공개 개작 프롤로그")

        assertThat(detailPrologue(chat, reader)).isEqualTo("두 번째 프롤로그")
    }

    @Test
    fun `공개 상태에서 고친 프롤로그는 비공개 전환 뒤에도 마지막 공개 버전으로 보인다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)

        patchPrologueWhilePublic(story, "v2 프롤로그")
        hideAndRename(story, "바뀐 제목")
        changePrologue(story, "비공개 개작 프롤로그")

        assertThat(detailPrologue(chat, reader)).isEqualTo("v2 프롤로그")
    }

    @Test
    fun `비공개로 되돌리고 프롤로그를 바꿔도 채팅 상세는 스냅샷 프롤로그를 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)

        hideAndRename(story, "바뀐 제목")
        changePrologue(story, "바뀐 프롤로그")

        assertThat(detailPrologue(chat, reader)).isEqualTo("원래 프롤로그")
    }

    @Test
    fun `소유자 본인은 비공개 스토리도 상세에서 현재 프롤로그를 본다`() {
        val owner = saveUser("소유자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, owner)

        hideAndRename(story, "바뀐 제목")
        changePrologue(story, "바뀐 프롤로그")

        assertThat(detailPrologue(chat, owner)).isEqualTo("바뀐 프롤로그")
    }

    @Test
    fun `비공개로 되돌리고 프롤로그를 바꾸면 공유 열람은 스냅샷 프롤로그를 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)
        val shareId = createShare(chat, reader)

        hideAndRename(story, "바뀐 제목")
        changePrologue(story, "바뀐 프롤로그")

        // 프롤로그는 스토리 도입부 본문이라 제목보다 유출 폭이 크다. 무인증 링크로 새면 안 된다.
        assertThat(sharePrologue(shareId)).isEqualTo("원래 프롤로그")
        // 같은 링크라도 스토리 소유자가 열면 현재 본문을 본다.
        assertThat(sharePrologue(shareId, viewer = owner)).isEqualTo("바뀐 프롤로그")
    }

    @Test
    fun `공개 스토리의 공유 열람은 현재 프롤로그를 따라간다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)
        val shareId = createShare(chat, reader)

        changePrologue(story, "바뀐 프롤로그")

        assertThat(sharePrologue(shareId)).isEqualTo("바뀐 프롤로그")
    }

    // ---- 추천 입력 ----

    @Test
    fun `비공개로 되돌리고 추천 입력을 바꾸면 채팅 상세의 추천 입력은 비어 있다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        seedSuggestedInput(story, "원래 추천 입력")
        val chat = createChat(story, reader)

        hideAndRename(story, "바뀐 제목")
        seedSuggestedInput(story, "바뀐 추천 입력")

        // 추천 입력은 목록이라 스냅샷하지 않고 게이트로 막는다 — 입력을 돕는 보조 장치라 없어도 채팅이 성립한다.
        assertThat(detailSuggestedInputs(chat, reader)).isEmpty()
    }

    @Test
    fun `소유자 본인은 비공개 스토리도 상세에서 현재 추천 입력을 본다`() {
        val owner = saveUser("소유자")
        val story = publicStoryWithPrologue(owner)
        seedSuggestedInput(story, "원래 추천 입력")
        val chat = createChat(story, owner)

        hideAndRename(story, "바뀐 제목")
        seedSuggestedInput(story, "바뀐 추천 입력")

        assertThat(detailSuggestedInputs(chat, owner)).containsExactly("바뀐 추천 입력")
    }

    @Test
    fun `공개 스토리라면 상세에 현재 추천 입력이 그대로 실린다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        seedSuggestedInput(story, "원래 추천 입력")
        val chat = createChat(story, reader)

        assertThat(detailSuggestedInputs(chat, reader)).containsExactly("원래 추천 입력")
    }


    // ---- 도달 엔딩 이름 ----

    @Test
    fun `비공개로 되돌리고 엔딩 이름을 바꿔도 서재는 스냅샷 이름을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)
        val ending = reachEnding(story, chat, "원래 엔딩")

        hideAndRename(story, "바뀐 제목")
        renameEnding(ending, "바뀐 엔딩")

        assertThat(libraryCard(reader).reachedEndings).containsExactly("원래 엔딩")
    }

    @Test
    fun `비공개로 되돌리고 엔딩 이름을 바꿔도 채팅 상세는 스냅샷 이름을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)
        val ending = reachEnding(story, chat, "원래 엔딩")

        hideAndRename(story, "바뀐 제목")
        renameEnding(ending, "바뀐 엔딩")

        assertThat(detailReachedEndings(chat, reader)).containsExactly("원래 엔딩")
    }

    @Test
    fun `비공개로 되돌리고 엔딩 이름을 바꾸면 공유 열람은 스냅샷 이름을 보여준다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)
        val ending = reachEnding(story, chat, "원래 엔딩")
        val shareId = createShare(chat, reader)

        hideAndRename(story, "바뀐 제목")
        renameEnding(ending, "바뀐 엔딩")

        // 무인증 링크로 새면 안 된다.
        assertThat(shareReachedEndings(shareId)).containsExactly("원래 엔딩")
        // 스토리 소유자가 열면 현재 이름을 본다.
        assertThat(shareReachedEndings(shareId, viewer = owner)).containsExactly("바뀐 엔딩")
    }

    @Test
    fun `공개 스토리면 엔딩 이름은 현재 값을 따라간다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)
        val ending = reachEnding(story, chat, "원래 엔딩")

        renameEnding(ending, "바뀐 엔딩")

        assertThat(libraryCard(reader).reachedEndings).containsExactly("바뀐 엔딩")
        assertThat(detailReachedEndings(chat, reader)).containsExactly("바뀐 엔딩")
    }

    @Test
    fun `엔딩에 도달하지 않은 채팅은 그대로 비어 있다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)

        hideAndRename(story, "바뀐 제목")

        assertThat(libraryCard(reader).reachedEndings).isEmpty()
    }

    // ---- 엔딩 행이 사라져 FK가 끊긴 경우 ----

    @Test
    fun `엔딩 행이 삭제돼 참조가 끊기면 도달 기록을 복구하지 않는다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        val chat = createChat(story, reader)
        val ending = reachEnding(story, chat, "원래 엔딩")

        breakEndingReference(chat, ending)

        // 스토리 스냅샷은 **엔딩 id로 이름을 찾는** 구조인데, FK(ON DELETE SET NULL)가 채팅·메시지의
        // reached_ending_id를 함께 비우므로 연결 고리가 남지 않는다. KNK-1059가 채팅에 이름 컬럼을 두어
        // 서재만 살렸던 복구는 그 컬럼과 함께 사라졌다(KNK-1065 결정 3).
        assertThat(libraryCard(reader).reachedEndings).isEmpty()
        assertThat(detailReachedEndings(chat, reader)).containsExactly(null as String?)
    }

    @Test
    fun `도달한 적 없는 채팅은 서재가 비어 있다`() {
        val owner = saveUser("소유자")
        val reader = saveUser("독자")
        val story = publicStoryWithPrologue(owner)
        createChat(story, reader)

        assertThat(libraryCard(reader).reachedEndings).isEmpty()
    }
}
