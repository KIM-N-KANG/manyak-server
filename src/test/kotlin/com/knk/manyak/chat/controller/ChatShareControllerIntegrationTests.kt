package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * 채팅 공유 발급·열람(스펙 §4-3-11, KNK-706).
 *
 * 발급은 채팅 상세 조회와 같은 소유권 게이트를 쓰고, 열람은 인증 없이 공유 토큰(UUID) 보유만으로 열린다.
 * 시점 고정은 메시지 복사가 아니라 발급 시점 current_turn을 커트라인으로 기록하는 방식이다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatShareControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storyEndingRepository: StoryEndingRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var storyChoiceRepository: StoryChoiceRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    // 이관 클레임(@Modifying)은 트랜잭션 안에서만 실행할 수 있어 테스트에서 직접 경계를 연다.
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    // ---- 발급(POST /chats/{chatId}/shares) ----

    @Test
    fun `게스트 채팅 공유를 발급하면 201과 shareId·turnCount·createdAt을 반환한다`() {
        val fixture = seedChat(turnCount = 2)

        restTestClient.post()
            .uri("/api/v1/chats/${fixture.chat.publicId}/shares")
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.shareId").isNotEmpty
            // 공유 토큰은 채팅 공개 식별자와 무관한 별도 UUID다.
            .jsonPath("$.shareId").value<String> { shareId ->
                check(shareId != fixture.chat.publicId.toString()) { "shareId가 chatId와 같으면 안 된다." }
                UUID.fromString(shareId)
            }
            .jsonPath("$.turnCount").isEqualTo(2)
            .jsonPath("$.createdAt").isNotEmpty
    }

    @Test
    fun `같은 커트라인으로 다시 발급하면 새로 만들지 않고 같은 shareId를 반환한다`() {
        val fixture = seedChat(turnCount = 2)

        val first = issueShare(fixture.chat)
        val second = issueShare(fixture.chat)

        check(first == second) { "멱등 재발급이어야 한다: $first != $second" }
    }

    @Test
    fun `턴이 진행된 뒤 발급하면 새 커트라인의 공유가 새로 생긴다`() {
        val fixture = seedChat(turnCount = 1)
        val firstShare = issueShare(fixture.chat)

        appendTurn(fixture, "마법수정에 손을 올린다.", "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")
        val secondShare = issueShare(fixture.chat)

        check(firstShare != secondShare) { "커트라인이 다르면 별도 공유여야 한다." }
        // 기존 공유도 계속 유효하고, 그 시점 내용만 보여준다.
        restTestClient.get()
            .uri("/api/v1/shares/$firstShare")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turns.length()").isEqualTo(1)
    }

    @Test
    fun `회원 소유 채팅 공유는 소유자만 발급할 수 있다`() {
        val owner = saveUser("소유자")
        val other = saveUser("타인")
        val fixture = seedChat(turnCount = 1, ownerId = owner.id)

        restTestClient.post()
            .uri("/api/v1/chats/${fixture.chat.publicId}/shares")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(owner.publicId)}")
            .exchange()
            .expectStatus().isCreated

        // 타인 회원: 403
        restTestClient.post()
            .uri("/api/v1/chats/${fixture.chat.publicId}/shares")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(other.publicId)}")
            .exchange()
            .expectStatus().isForbidden

        // 미인증: 403
        restTestClient.post()
            .uri("/api/v1/chats/${fixture.chat.publicId}/shares")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `회원이 게스트(NULL) 소유 채팅 공유를 발급하면 403이다`() {
        val member = saveUser("회원")
        val fixture = seedChat(turnCount = 1)

        restTestClient.post()
            .uri("/api/v1/chats/${fixture.chat.publicId}/shares")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `이관으로 소유자가 생긴 게스트 채팅은 이후 익명 발급이 403이다`() {
        // Codex P2 회귀 가드: 발급이 채팅 행 락 없이 소유권을 검사하면, 이관 클레임(claimByPublicId)이 방금
        // 소유자를 박은 뒤에도 stale한 user_id == null 판정으로 익명 공유가 만들어진다. 발급은 커밋된 최신
        // 소유 상태를 봐야 한다 — 이관 후 익명 요청은 403이고, 이관 전 발급분은 그대로 유효하다.
        val member = saveUser("이관한 회원")
        val fixture = seedChat(turnCount = 1)
        val beforeMigration = issueShare(fixture.chat)

        val claimed = transactionTemplate.execute {
            storyChatRepository.claimByPublicId(fixture.chat.publicId, member.id)
        }
        check(claimed == 1) { "이관 클레임이 적용되지 않았습니다. actual=$claimed" }

        restTestClient.post()
            .uri("/api/v1/chats/${fixture.chat.publicId}/shares")
            .exchange()
            .expectStatus().isForbidden

        // 이관 전에 발급된 공유는 계속 열린다(공유 해지 수단은 채팅 삭제뿐).
        restTestClient.get()
            .uri("/api/v1/shares/$beforeMigration")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `커트라인이 전체 턴보다 작으면 커트라인까지의 턴만 순서대로 반환한다`() {
        // 커트라인 조회를 message_order 상한(2N)으로 좁힌 뒤에도 경계가 정확한지 고정한다(Codex P2).
        val fixture = seedChat(turnCount = 2)
        val shareId = issueShare(fixture.chat)
        appendTurn(fixture, "세 번째 입력", "세 번째 응답")

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turns.length()").isEqualTo(2)
            .jsonPath("$.turns[0].userInput").isEqualTo("이름은 강진우야.")
            .jsonPath("$.turns[1].userInput").isEqualTo("턴 2 입력")
            .jsonPath("$.turns[1].aiOutput").isEqualTo("턴 2 응답")
    }

    @Test
    fun `SYSTEM 메시지가 낀 채팅도 1턴 공유가 1턴을 정확히 반환한다`() {
        // Codex P2 회귀 가드: 커트라인 경계를 message_order 산술(턴 N = 2N)로 구하면, SYSTEM 1건(order 1)이
        // 앞에 낀 형태(ChatStreamHistoryIntegrationTests가 고정한 지원 형태)에서 마지막 턴이 통째로 누락된다.
        // 1턴 공유가 0턴이 되는 것이 그 증상이다. 경계는 논리 턴(N번째 ASSISTANT) 기준이어야 한다.
        val fixture = seedChatWithSystemPrefix(turnCount = 1)
        val shareId = issueShare(fixture.chat)

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turns.length()").isEqualTo(1)
            .jsonPath("$.turns[0].userInput").isEqualTo("유저 메시지 1")
            .jsonPath("$.turns[0].aiOutput").isEqualTo("AI 응답 1")
    }

    @Test
    fun `SYSTEM 메시지가 낀 채팅의 커트라인 경계도 논리 턴 기준으로 맞는다`() {
        // 3턴 중 2턴 시점에 발급 → 발급분 2턴만, 마지막 턴은 제외. order 산술이면 여기서도 턴이 밀린다.
        val fixture = seedChatWithSystemPrefix(turnCount = 2)
        val shareId = issueShare(fixture.chat)
        appendSystemPrefixTurn(fixture, turn = 3)

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turns.length()").isEqualTo(2)
            .jsonPath("$.turns[0].aiOutput").isEqualTo("AI 응답 1")
            .jsonPath("$.turns[1].userInput").isEqualTo("유저 메시지 2")
            .jsonPath("$.turns[1].aiOutput").isEqualTo("AI 응답 2")
    }

    @Test
    fun `없는 채팅으로 공유를 발급하면 404다`() {
        restTestClient.post()
            .uri("/api/v1/chats/${UUID.randomUUID()}/shares")
            .exchange()
            .expectStatus().isNotFound

        // 순차 정수(형식 오류)도 동일하게 404 — 존재 여부를 노출하지 않는다.
        restTestClient.post()
            .uri("/api/v1/chats/999999/shares")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `소프트 삭제된 채팅으로 공유를 발급하면 404다`() {
        val fixture = seedChat(turnCount = 1)
        softDelete(fixture.chat)

        restTestClient.post()
            .uri("/api/v1/chats/${fixture.chat.publicId}/shares")
            .exchange()
            .expectStatus().isNotFound
    }

    // ---- 열람(GET /shares/{shareId}) ----

    @Test
    fun `공유 열람은 인증 없이 200이며 스토리 제목·프롤로그와 커트라인 이하 턴을 반환한다`() {
        val fixture = seedChat(turnCount = 2)
        val shareId = issueShare(fixture.chat)

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(shareId)
            .jsonPath("$.storyId").isEqualTo(fixture.story.publicId.toString())
            .jsonPath("$.storyTitle").isEqualTo("호아킨 아카데미의 무속성 신입생")
            .jsonPath("$.prologue").isEqualTo("마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.")
            .jsonPath("$.turns.length()").isEqualTo(2)
            .jsonPath("$.turns[0].userInput").isEqualTo("이름은 강진우야.")
            .jsonPath("$.turns[0].aiOutput").isEqualTo("강진우라는 이름이 기록판에 새겨졌다.")
            .jsonPath("$.turns[0].reachedEnding").isEmpty()
            .jsonPath("$.turns[0].createdAt").isNotEmpty
    }

    @Test
    fun `공유 열람 응답은 choices·suggestedInputs·원본 chatId를 싣지 않는다`() {
        val fixture = seedChat(turnCount = 1)
        // 커트라인 이내 턴에 선택지가 있어도 열람에는 노출하지 않는다.
        storyChoiceRepository.save(
            StoryChoice(
                chatId = fixture.chat.id,
                messageId = fixture.assistantIds.last(),
                choiceText = "주변을 살핀다.",
                choiceOrder = 1.toShort(),
            ),
        )
        storySuggestedInputRepository.save(
            StorySuggestedInput(startSetting = fixture.startSetting, inputText = "검사장을 둘러본다.", inputOrder = 1),
        )
        val shareId = issueShare(fixture.chat)

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turns[0].choices").doesNotExist()
            .jsonPath("$.suggestedInputs").doesNotExist()
            .jsonPath("$.chatId").doesNotExist()
    }

    @Test
    fun `발급 후 턴이 진행돼도 공유 열람은 커트라인 이하 턴만 반환한다`() {
        val fixture = seedChat(turnCount = 1)
        val shareId = issueShare(fixture.chat)

        appendTurn(fixture, "마법수정에 손을 올린다.", "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.")

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turns.length()").isEqualTo(1)
            .jsonPath("$.turns[0].aiOutput").isEqualTo("강진우라는 이름이 기록판에 새겨졌다.")
    }

    @Test
    fun `커트라인 이내 턴이 재생성되면 공유 열람에도 활성본이 반영된다`() {
        // 시점 고정은 메시지 복사가 아니라 커트라인이므로, 커트라인 이내 턴의 재생성 결과는 열람에 반영된다(§4-3-11).
        val fixture = seedChat(turnCount = 1)
        val shareId = issueShare(fixture.chat)

        // 재생성(§4-3-9)은 마지막 ASSISTANT 활성본을 제자리 교체한다(턴 수는 그대로).
        val assistant = storyMessageRepository.findById(fixture.assistantIds.last()).orElseThrow()
        assistant.content = "재생성된 이야기: 기록판의 이름이 붉게 물들었다."
        storyMessageRepository.save(assistant)

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turns.length()").isEqualTo(1)
            .jsonPath("$.turns[0].aiOutput").isEqualTo("재생성된 이야기: 기록판의 이름이 붉게 물들었다.")
    }

    @Test
    fun `공유 열람 턴은 도달 엔딩을 이름으로 노출한다`() {
        val fixture = seedChat(turnCount = 0)
        val ending = storyEndingRepository.save(
            StoryEnding(
                startSetting = fixture.startSetting,
                name = "각성한 무속성",
                minTurns = 1,
                achievementCondition = "숨어 있던 힘을 각성한다",
                epilogue = "전설의 시작이었다.",
                sortOrder = 1,
            ),
        )
        message(fixture.chat.id, MessageRole.USER, "깨진 수정에 손을 뻗는다.", 1)
        storyMessageRepository.save(
            StoryMessage(
                chatId = fixture.chat.id,
                role = MessageRole.ASSISTANT,
                content = "깨진 수정 위로 무속성의 빛이 폭발했다.",
                messageOrder = 2,
                reachedEndingId = ending.id,
            ),
        )
        val chat = storyChatRepository.findById(fixture.chat.id).orElseThrow()
        chat.currentTurn = 1
        storyChatRepository.save(chat)
        val shareId = issueShare(fixture.chat)

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turns[0].reachedEnding").isEqualTo("각성한 무속성")
    }

    @Test
    fun `원본 채팅이 소프트 삭제되면 공유 열람은 404다`() {
        val fixture = seedChat(turnCount = 1)
        val shareId = issueShare(fixture.chat)

        softDelete(fixture.chat)

        restTestClient.get()
            .uri("/api/v1/shares/$shareId")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `없는 shareId나 형식이 잘못된 shareId로 열람하면 404다`() {
        restTestClient.get()
            .uri("/api/v1/shares/${UUID.randomUUID()}")
            .exchange()
            .expectStatus().isNotFound

        // 형식 오류(순차 정수·임의 문자열)도 동일하게 404 — 존재 여부를 노출하지 않는다.
        restTestClient.get()
            .uri("/api/v1/shares/999999")
            .exchange()
            .expectStatus().isNotFound

        restTestClient.get()
            .uri("/api/v1/shares/not-a-uuid")
            .exchange()
            .expectStatus().isNotFound
    }

    // ---- 픽스처 ----

    private data class ChatFixture(
        val story: Story,
        val startSetting: StoryStartSetting,
        val chat: StoryChat,
        val assistantIds: List<Long>,
        var messageOrder: Int,
    )

    /** 스토리·시작 설정과 [turnCount]턴이 진행된 게스트/회원 채팅을 만든다. */
    private fun seedChat(turnCount: Int, ownerId: Long? = null): ChatFixture {
        val story = storyRepository.save(Story(title = "호아킨 아카데미의 무속성 신입생"))
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "입학 적성 검사",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사 직전의 검사장.",
            ),
        )
        val chat = storyChatRepository.save(
            StoryChat(
                storyId = story.id,
                startSettingId = startSetting.id,
                userId = ownerId,
                currentTurn = turnCount,
            ),
        )
        val assistantIds = mutableListOf<Long>()
        var order = 0
        repeat(turnCount) { index ->
            message(chat.id, MessageRole.USER, if (index == 0) "이름은 강진우야." else "턴 ${index + 1} 입력", ++order)
            val assistant = message(
                chat.id,
                MessageRole.ASSISTANT,
                if (index == 0) "강진우라는 이름이 기록판에 새겨졌다." else "턴 ${index + 1} 응답",
                ++order,
            )
            assistantIds += assistant.id
        }
        return ChatFixture(story, startSetting, chat, assistantIds, order)
    }

    /**
     * SYSTEM 1건(order 1) + 턴 N이 order 2N·2N+1인 형태로 채팅을 만든다.
     * `ChatStreamHistoryIntegrationTests`·`ChatRegenerateHistoryIntegrationTests`가 고정한 지원 형태이며,
     * 턴↔message_order가 2N으로 떨어지지 않아 order 산술 기반 커트라인이 깨지는 경우다.
     */
    private fun seedChatWithSystemPrefix(turnCount: Int): ChatFixture {
        val story = storyRepository.save(Story(title = "호아킨 아카데미의 무속성 신입생"))
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "입학 적성 검사",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사 직전의 검사장.",
            ),
        )
        val chat = storyChatRepository.save(
            StoryChat(storyId = story.id, startSettingId = startSetting.id, currentTurn = turnCount),
        )
        message(chat.id, MessageRole.SYSTEM, "시스템 지시문", 1)
        val assistantIds = mutableListOf<Long>()
        repeat(turnCount) { index ->
            val turn = index + 1
            message(chat.id, MessageRole.USER, "유저 메시지 $turn", turn * 2)
            assistantIds += message(chat.id, MessageRole.ASSISTANT, "AI 응답 $turn", turn * 2 + 1).id
        }
        return ChatFixture(story, startSetting, chat, assistantIds, turnCount * 2 + 1)
    }

    /** [seedChatWithSystemPrefix] 형태에 턴을 하나 더 붙인다(커트라인 이후 진행). */
    private fun appendSystemPrefixTurn(fixture: ChatFixture, turn: Int) {
        message(fixture.chat.id, MessageRole.USER, "유저 메시지 $turn", turn * 2)
        message(fixture.chat.id, MessageRole.ASSISTANT, "AI 응답 $turn", turn * 2 + 1)
        val chat = storyChatRepository.findById(fixture.chat.id).orElseThrow()
        chat.currentTurn = turn
        storyChatRepository.save(chat)
    }

    /** 커트라인 이후 진행을 흉내낸다(메시지 2개 추가 + current_turn 증가). */
    private fun appendTurn(fixture: ChatFixture, userInput: String, aiOutput: String) {
        message(fixture.chat.id, MessageRole.USER, userInput, ++fixture.messageOrder)
        message(fixture.chat.id, MessageRole.ASSISTANT, aiOutput, ++fixture.messageOrder)
        val chat = storyChatRepository.findById(fixture.chat.id).orElseThrow()
        chat.currentTurn += 1
        storyChatRepository.save(chat)
    }

    private fun softDelete(chat: StoryChat) {
        val persisted = storyChatRepository.findById(chat.id).orElseThrow()
        persisted.deletedAt = Instant.parse("2026-07-29T10:00:00Z")
        storyChatRepository.save(persisted)
    }

    /** 공유를 발급하고 shareId를 돌려준다. */
    private fun issueShare(chat: StoryChat): String =
        String(
            restTestClient.post()
                .uri("/api/v1/chats/${chat.publicId}/shares")
                .exchange()
                .expectStatus().isCreated
                .expectBody()
                .returnResult()
                .responseBody!!,
        ).substringAfter("\"shareId\":\"").substringBefore("\"")

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun message(chatId: Long, role: MessageRole, content: String, order: Int): StoryMessage =
        storyMessageRepository.save(
            StoryMessage(
                chatId = chatId,
                role = role,
                content = content,
                messageOrder = order,
            ),
        )
}
