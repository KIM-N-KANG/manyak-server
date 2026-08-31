package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatCharacterImageEvent
import com.knk.manyak.chat.client.ChatMessageRole
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatChoicesResult
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryPublicSnapshotRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.service.StoryPublicSnapshotService
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.concurrent.atomic.AtomicReference

/**
 * 이어쓰기 시 AI 서버로 보내는 history가 "최근 N턴"이 아니라 세션의 전체 대화 내역을
 * 시간순으로 담는지 검증한다. AI 클라이언트를 전달받은 요청을 캡처하는 가짜로 교체한다.
 *
 * 가짜 AI 빈 때문에 별도 ApplicationContext가 만들어지므로, 기본 테스트 컨텍스트와
 * 같은 이름의 in-memory H2를 공유하지 않도록 전용 DB 이름으로 분리한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-history-capture;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class ChatStreamHistoryIntegrationTests {

    /** 전달받은 [ChatTurnAiRequest]를 캡처하고 정상 결과를 반환하는 가짜 AI 클라이언트. */
    class CapturingChatTurnAiClient : ChatTurnAiClient {
        val lastRequest = AtomicReference<ChatTurnAiRequest>()

        override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String, traceLink: AiTraceLink): ChatChoicesResult = ChatChoicesResult(emptyList())
        override fun streamTurn(
            request: ChatTurnAiRequest,
            traceLink: AiTraceLink,
            onCharacterImage: (ChatCharacterImageEvent) -> Unit,
            onToken: (String) -> Unit,
        ): ChatTurnAiResult {
            lastRequest.set(request)
            onToken("응답")
            return ChatTurnAiResult(aiOutput = "응답 본문", choices = listOf("선택 1", "선택 2"))
        }
    }

    @TestConfiguration
    class CapturingAiClientConfig {
        @Bean
        @Primary
        fun capturingChatTurnAiClient(): CapturingChatTurnAiClient = CapturingChatTurnAiClient()
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var capturingAiClient: CapturingChatTurnAiClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var startSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var settingRepository: StorySettingRepository

    @Autowired
    private lateinit var mainEventRepository: StoryMainEventRepository

    @Autowired
    private lateinit var endingRepository: StoryEndingRepository

    @Autowired
    private lateinit var snapshotService: StoryPublicSnapshotService

    @Autowired
    private lateinit var snapshotRowRepository: StoryPublicSnapshotRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `이어쓰기는 최근 N턴이 아니라 세션의 전체 대화 내역을 시간순으로 AI에 전달한다`() {
        val story = storyRepository.save(Story(title = "전체 내역 전송 스토리", genre = "판타지"))
        val session = storyChatRepository.save(StoryChat(storyId = story.id))

        // SYSTEM 1건(order 1) + 11턴(USER/ASSISTANT, order 2..23) = 23건.
        // 최근 10턴 제한(메시지 20건)으로는 전체가 담기지 않는다.
        storyMessageRepository.save(
            StoryMessage(chatId = session.id, role = MessageRole.SYSTEM, content = "시스템 지시문", messageOrder = 1),
        )
        repeat(11) { i ->
            val turn = i + 1
            storyMessageRepository.save(
                StoryMessage(chatId = session.id, role = MessageRole.USER, content = "유저 메시지 $turn", messageOrder = turn * 2),
            )
            storyMessageRepository.save(
                StoryMessage(chatId = session.id, role = MessageRole.ASSISTANT, content = "AI 응답 $turn", messageOrder = turn * 2 + 1),
            )
        }

        stream(session.publicId.toString(), "다음 행동을 한다.")

        val captured = capturingAiClient.lastRequest.get()
            ?: error("AI 요청이 캡처되지 않았습니다.")

        // 전체 USER/ASSISTANT 22건이 그대로 전달된다(최근 10턴=20건 제한이 아니다).
        assertThat(captured.history).hasSize(22)
        // 시간순: 가장 오래된 USER가 처음, 직전 ASSISTANT가 마지막
        assertThat(captured.history.first().role).isEqualTo(ChatMessageRole.USER)
        assertThat(captured.history.first().content).isEqualTo("유저 메시지 1")
        assertThat(captured.history.last().role).isEqualTo(ChatMessageRole.ASSISTANT)
        assertThat(captured.history.last().content).isEqualTo("AI 응답 11")
        // SYSTEM 메시지는 history에서 제외한다.
        assertThat(captured.history).noneMatch { it.content == "시스템 지시문" }
        // 현재 입력은 아직 저장 전이므로 history에 포함되지 않는다.
        assertThat(captured.history).noneMatch { it.content == "다음 행동을 한다." }
        // 메모리(summary)는 아직 미적용 → 빈 문자열.
        assertThat(captured.summary).isEmpty()
    }

    // ---- [KNK-1064] 프롤로그 스냅샷 ----

    /** 회원 소유 공개 스토리 + 시작 설정 + 그 스토리로 시작한 게스트 채팅. 채팅 생성은 스냅샷을 박는 실제 API로 한다. */
    private fun seedChatOnPublicStory(): Pair<Story, StoryChat> {
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        val story = storyRepository.save(Story(userId = owner.id, title = "프롤로그 스냅샷 스토리", genre = "판타지"))
        startSettingRepository.save(
            StoryStartSetting(story = story, name = "시작 장면", prologue = "원래 프롤로그"),
        )
        // 공개 상태로 저장된 스토리는 마지막 공개 버전 스냅샷을 들고 있다(KNK-1065).
        publish(story)
        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isCreated
        return story to storyChatRepository.findAll().first { it.storyId == story.id }
    }

    private fun changePrologue(story: Story, prologue: String) {
        val setting = startSettingRepository.findAll().first { it.story.id == story.id }
        setting.prologue = prologue
        startSettingRepository.save(setting)
    }

    /** 공개 상태 저장 = 스냅샷 갱신. 실제로는 제작 등록·수정 API가 이 지점을 탄다. */
    private fun publish(story: Story) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        snapshotService.refresh(loaded)
        storyRepository.save(loaded)
    }

    private fun hideStory(story: Story) {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.status = StoryStatus.DRAFT
        loaded.visibility = StoryVisibility.PRIVATE
        storyRepository.save(loaded)
    }

    @Test
    fun `공개 스토리의 프롤로그를 고치면 다음 턴 요청에 현재 값이 실린다`() {
        val (story, chat) = seedChatOnPublicStory()

        changePrologue(story, "바뀐 프롤로그")

        stream(chat.publicId.toString(), "다음 행동을 한다.")

        // 공개 스토리는 제작자의 밸런스 패치가 진행 중인 채팅에도 반영돼야 한다.
        assertThat(capturingAiClient.lastRequest.get().startSettings.prologue).isEqualTo("바뀐 프롤로그")
    }

    @Test
    fun `비공개로 되돌린 뒤 프롤로그를 고치면 다음 턴 요청에는 스냅샷이 실린다`() {
        val (story, chat) = seedChatOnPublicStory()

        hideStory(story)
        changePrologue(story, "바뀐 프롤로그")

        stream(chat.publicId.toString(), "다음 행동을 한다.")

        // 화면에서 막아놓은 개작 내용이 생성 결과를 통해 새어 나가면 안 된다.
        assertThat(capturingAiClient.lastRequest.get().startSettings.prologue).isEqualTo("원래 프롤로그")
    }

    @Test
    fun `재생성 경로도 같은 조립부를 써서 비공개 스토리에는 스냅샷을 싣는다`() {
        val (story, chat) = seedChatOnPublicStory()
        // 재생성 대상이 될 마지막 턴 1건.
        storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "첫 입력", messageOrder = 1),
        )
        val lastTurn = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "첫 응답", messageOrder = 2),
        )
        storyChatRepository.save(chat.also { it.currentTurn = 1 })

        hideStory(story)
        changePrologue(story, "바뀐 프롤로그")

        regenerate(chat.publicId.toString(), lastTurn.id)

        // 이어쓰기·재생성·선택지가 buildAiRequest 하나를 공유하므로 규칙이 갈라지지 않는다.
        assertThat(capturingAiClient.lastRequest.get().startSettings.prologue).isEqualTo("원래 프롤로그")
    }

    // ---- [KNK-1065] 스토리별 "마지막 공개 버전" 스냅샷 ----

    @Test
    fun `공개 상태에서 고친 프롤로그는 비공개 전환 뒤에도 마지막 공개 버전으로 실린다`() {
        val (story, chat) = seedChatOnPublicStory()

        // 제작자가 공개를 유지한 채 밸런스 패치(v2)를 한다 — 독자는 이 값을 보고 있었다.
        changePrologue(story, "v2 프롤로그")
        publish(story)
        // 그 뒤 감추고 개작한다.
        hideStory(story)
        changePrologue(story, "비공개 개작 프롤로그")

        stream(chat.publicId.toString(), "다음 행동을 한다.")

        // 채팅 생성 시점(v1)이 아니라 **마지막 공개 시점(v2)** 이어야 한다.
        assertThat(capturingAiClient.lastRequest.get().startSettings.prologue).isEqualTo("v2 프롤로그")
    }

    @Test
    fun `비공개로 되돌린 뒤 고친 설정과 장르는 턴 요청에 실리지 않는다`() {
        val (story, chat) = seedChatOnPublicStory()
        settingRepository.save(
            StorySetting(
                story = story,
                worldSetting = "공개 세계관",
                characterSetting = "공개 인물",
                userRoleSetting = "공개 역할",
                ruleSetting = "공개 규칙",
            ),
        )
        publish(story)

        hideStory(story)
        val setting = settingRepository.findByStoryId(story.id)!!
        setting.worldSetting = "비공개 개작 세계관"
        setting.characterSetting = "비공개 개작 인물"
        setting.userRoleSetting = "비공개 개작 역할"
        setting.ruleSetting = "비공개 개작 규칙"
        settingRepository.save(setting)
        storyRepository.save(storyRepository.findById(story.id).orElseThrow().also { it.genre = "비공개 개작 장르" })

        stream(chat.publicId.toString(), "다음 행동을 한다.")

        val captured = capturingAiClient.lastRequest.get()
        assertThat(captured.storySettings.worldSetting).isEqualTo("공개 세계관")
        assertThat(captured.storySettings.characterSetting).isEqualTo("공개 인물")
        assertThat(captured.storySettings.userRoleSetting).isEqualTo("공개 역할")
        assertThat(captured.storySettings.ruleSetting).isEqualTo("공개 규칙")
        assertThat(captured.genre).isEqualTo("판타지")
    }

    @Test
    fun `비공개로 되돌린 뒤 고친 주요 사건과 엔딩은 턴 요청에 실리지 않는다`() {
        val (story, chat) = seedChatOnPublicStory()
        val startSetting = startSettingRepository.findAll().first { it.story.id == story.id }
        mainEventRepository.save(
            StoryMainEvent(story = story, name = "공개 사건", description = "공개 설명", keySentence = "공개 문장", sortOrder = 0),
        )
        endingRepository.save(
            StoryEnding(
                startSetting = startSetting,
                name = "공개 엔딩",
                minTurns = 1,
                achievementCondition = "공개 조건",
                epilogue = "공개 에필로그",
                sortOrder = 1,
            ),
        )
        publish(story)

        hideStory(story)
        // 수정 API의 전체 교체와 같은 결과(행 삭제 후 재생성)를 만든다.
        mainEventRepository.deleteAll(mainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id))
        mainEventRepository.flush()
        mainEventRepository.save(
            StoryMainEvent(story = story, name = "비공개 사건", description = "비공개 설명", keySentence = "비공개 문장", sortOrder = 0),
        )
        jdbcTemplate.update("DELETE FROM story_endings WHERE start_setting_id = ?", startSetting.id)
        endingRepository.save(
            StoryEnding(
                startSetting = startSetting,
                name = "비공개 엔딩",
                minTurns = 1,
                achievementCondition = "비공개 조건",
                epilogue = "비공개 에필로그",
                sortOrder = 1,
            ),
        )

        stream(chat.publicId.toString(), "다음 행동을 한다.")

        val captured = capturingAiClient.lastRequest.get()
        assertThat(captured.mainEvents.map { it.name }).containsExactly("공개 사건")
        assertThat(captured.mainEvents.map { it.description }).containsExactly("공개 설명")
        assertThat(captured.endings.map { it.name }).containsExactly("공개 엔딩")
        assertThat(captured.endings.map { it.epilogue }).containsExactly("공개 에필로그")
    }

    @Test
    fun `다시 공개하면 턴 요청이 현재 값으로 복귀한다`() {
        val (story, chat) = seedChatOnPublicStory()

        hideStory(story)
        changePrologue(story, "개작 프롤로그")
        // 다시 공개로 되돌리면(= 공개 상태 저장) 개작본이 곧 현재 공개본이다.
        val loaded = storyRepository.findById(story.id).orElseThrow()
        loaded.status = StoryStatus.PUBLISHED
        loaded.visibility = StoryVisibility.PUBLIC
        storyRepository.save(loaded)
        publish(story)

        stream(chat.publicId.toString(), "다음 행동을 한다.")

        assertThat(capturingAiClient.lastRequest.get().startSettings.prologue).isEqualTo("개작 프롤로그")
    }

    @Test
    fun `스냅샷이 없는 비공개 스토리는 턴 요청 재료가 비어 있고 터지지 않는다`() {
        // 백필 대상 밖(백필 시점에 이미 비공개)인 스토리를 재현한다 — last_public_snapshot이 NULL이다.
        val (story, chat) = seedChatOnPublicStory()
        // 백필 대상 밖(백필 시점에 이미 비공개)인 스토리를 재현한다 — story_public_snapshots에 행이 없다.
        snapshotRowRepository.deleteById(story.id)
        hideStory(story)
        changePrologue(story, "비공개 개작 프롤로그")

        stream(chat.publicId.toString(), "다음 행동을 한다.")

        val captured = capturingAiClient.lastRequest.get()
        assertThat(captured.startSettings.prologue).isEmpty()
        assertThat(captured.genre).isEmpty()
        assertThat(captured.storySettings.worldSetting).isEmpty()
        assertThat(captured.mainEvents).isEmpty()
        assertThat(captured.endings).isEmpty()
    }

    private fun regenerate(chatId: String, turnId: Long): String =
        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/regenerate/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"turnId":$turnId}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("재생성 스트리밍 응답 본문이 비어 있습니다.")

    private fun stream(chatId: String, userInput: String): String =
        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"$userInput"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")
}
