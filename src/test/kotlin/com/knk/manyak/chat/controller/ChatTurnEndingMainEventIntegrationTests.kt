package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.client.ChatCharacterImageEvent
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatChoicesResult
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.client.ChatTurnTargetMainEventResult
import com.knk.manyak.chat.entity.ChatStatus
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatMainEventRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.UserStoryEndingReachRepository
import com.knk.manyak.story.service.StoryPublicSnapshotService
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
 * KNK-522(B5-C): 채팅 턴의 주요 사건·엔딩 런타임 연동.
 *
 * - 요청 조립: 주요 사건 전체·목표 사건·거쳐온 사건·(min_turns 충족·미도달) 엔딩 후보를 싣는다.
 * - completed 판정 반영: 목표 사건·진행 카운터·완결 사건 기록, 엔딩 도달 시 상태 ENDED·회원 집계·메시지 표식·SSE reachedEnding.
 *
 * AI 클라이언트를 요청 캡처 + 판정 결과 주입 가능한 가짜로 교체한다(가짜 빈 때문에 전용 H2로 분리).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-ending-runtime;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class ChatTurnEndingMainEventIntegrationTests {

    class JudgingChatTurnAiClient : ChatTurnAiClient {
        val lastRequest = AtomicReference<ChatTurnAiRequest>()

        @Volatile
        var result: ChatTurnAiResult = ChatTurnAiResult(aiOutput = "응답 본문", choices = listOf("선택 1"))

        override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String, traceLink: AiTraceLink): ChatChoicesResult = ChatChoicesResult(emptyList())
        override fun streamTurn(
        request: ChatTurnAiRequest,
        traceLink: AiTraceLink,
        onCharacterImage: (ChatCharacterImageEvent) -> Unit,
        onToken: (String) -> Unit,
    ): ChatTurnAiResult {
            lastRequest.set(request)
            onToken("응답")
            return result
        }
    }

    @TestConfiguration
    class JudgingAiClientConfig {
        @Bean
        @Primary
        fun judgingChatTurnAiClient(): JudgingChatTurnAiClient = JudgingChatTurnAiClient()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var judgingAiClient: JudgingChatTurnAiClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyStartSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var storyMainEventRepository: StoryMainEventRepository
    @Autowired private lateinit var storyEndingRepository: StoryEndingRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var storyChatMainEventRepository: StoryChatMainEventRepository
    @Autowired private lateinit var storyMessageRepository: StoryMessageRepository
    @Autowired private lateinit var userStoryEndingReachRepository: UserStoryEndingReachRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var snapshotService: StoryPublicSnapshotService
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var databaseCleaner: com.knk.manyak.support.DatabaseCleaner

    private lateinit var story: Story
    private lateinit var startSetting: StoryStartSetting
    private lateinit var eventBaldan: StoryMainEvent
    private lateinit var eventJeoljeong: StoryMainEvent
    private lateinit var happyEnding: StoryEnding

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "응답 본문", choices = listOf("선택 1"))
        story = storyRepository.save(Story(title = "런타임 스토리", genre = "판타지"))
        startSetting = storyStartSettingRepository.save(StoryStartSetting(story = story, name = "시작 설정"))
        eventBaldan = storyMainEventRepository.save(
            StoryMainEvent(story = story, name = "발단", description = "시작된다", keySentence = "길을 나선다", sortOrder = 0),
        )
        eventJeoljeong = storyMainEventRepository.save(
            StoryMainEvent(story = story, name = "절정", description = "최고조", keySentence = "결전을 벌인다", sortOrder = 1),
        )
        happyEnding = storyEndingRepository.save(
            StoryEnding(startSetting = startSetting, name = "해피", minTurns = 1, achievementCondition = "적을 물리친다", epilogue = "평화", sortOrder = 1),
        )
        // min_turns 5 엔딩은 현재 턴(1)에 후보로 실리면 안 된다.
        storyEndingRepository.save(
            StoryEnding(startSetting = startSetting, name = "머나먼", minTurns = 5, achievementCondition = "긴 여정", epilogue = "먼 훗날", sortOrder = 2),
        )
    }

    @Test
    fun `요청에 주요 사건 전체와 min_turns 충족 엔딩만 싣고, 목표·완결 판정을 상태에 반영한다`() {
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        judgingAiClient.result = ChatTurnAiResult(
            aiOutput = "응답 본문",
            choices = listOf("선택 1"),
            targetMainEvent = ChatTurnTargetMainEventResult(name = "절정", progressTurns = 2),
            occurredMainEventName = "발단",
        )

        streamGuest(chat.publicId.toString(), "결전을 준비한다.")

        val captured = judgingAiClient.lastRequest.get() ?: error("AI 요청이 캡처되지 않았습니다.")
        assertThat(captured.mainEvents.map { it.name }).containsExactly("발단", "절정")
        // min_turns 1만 충족(현재 턴=1). min_turns 5 엔딩은 제외.
        assertThat(captured.endings.map { it.name }).containsExactly("해피")
        // 첫 턴이라 목표·거쳐온 사건은 아직 없다.
        assertThat(captured.targetMainEvent).isNull()
        assertThat(captured.occurredMainEventNames).isEmpty()

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(updated.targetMainEventId).isEqualTo(eventJeoljeong.id)
        assertThat(updated.targetProgressTurns).isEqualTo(2)
        assertThat(updated.status).isEqualTo(ChatStatus.ACTIVE)
        assertThat(updated.reachedEndingId).isNull()

        // 완결된 '발단'이 story_chat_main_events에 기록된다.
        val occurred = storyChatMainEventRepository.findByChatId(chat.id)
        assertThat(occurred.map { it.mainEventId }).containsExactly(eventBaldan.id)
    }

    @Test
    fun `게스트 엔딩 도달 시 채팅이 ENDED로 굳고 메시지·SSE에 도달 엔딩이 실리며 회원 집계는 없다`() {
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        judgingAiClient.result = ChatTurnAiResult(
            aiOutput = "마침내 평화가 찾아왔다.",
            choices = emptyList(),
            endingName = "해피",
        )

        val body = streamGuest(chat.publicId.toString(), "최후의 일격을 가한다.")

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(updated.reachedEndingId).isEqualTo(happyEnding.id)
        // 도달 시점 이름(KNK-1059). 엔딩 행이 지워지면 reached_ending_id가 FK로 비워져 스토리 스냅샷의
        // "엔딩 id → 이름" 사전을 조회할 키가 사라지므로, 서재는 이 값으로 도달 기록을 복구한다.
        assertThat(updated.reachedEndingNameSnapshot).isEqualTo("해피")
        assertThat(updated.status).isEqualTo(ChatStatus.ENDED)

        // 도달 턴 ASSISTANT 메시지에 reached_ending_id 표식.
        val assistant = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id)
            .last { it.role == MessageRole.ASSISTANT }
        assertThat(assistant.reachedEndingId).isEqualTo(happyEnding.id)

        // SSE completed에 reachedEnding 동봉.
        assertThat(body).contains("reachedEnding")
        assertThat(body).contains("\"해피\"")

        // 게스트는 회원 도달 집계에 남지 않는다.
        assertThat(userStoryEndingReachRepository.findAll()).isEmpty()
    }

    @Test
    fun `AI가 min_turns 미충족 엔딩 이름을 보내도 도달로 인정하지 않는다`() {
        // '머나먼'은 min_turns=5. 첫 턴(생성 턴=1)엔 백엔드 결정 문턱을 넘지 못하므로 write-side에서 도달을 거절한다.
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "아직 끝이 아니다.", choices = listOf("계속"), endingName = "머나먼")

        streamGuest(chat.publicId.toString(), "먼 길을 떠난다.")

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(updated.reachedEndingId).isNull()
        assertThat(updated.status).isEqualTo(ChatStatus.ACTIVE)
        val assistant = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id)
            .last { it.role == MessageRole.ASSISTANT }
        assertThat(assistant.reachedEndingId).isNull()
    }

    @Test
    fun `회원 엔딩 도달은 user_story_ending_reaches에 최초 1회 집계된다`() {
        val member = userRepository.save(User(nickname = "회원", status = UserStatus.ACTIVE))
        creditWalletService.reward(member.id, 1000, CreditReason.SIGNUP_REWARD, "signup:${member.id}")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, userId = member.id, startSettingId = startSetting.id))
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "엔딩", choices = emptyList(), endingName = "해피")

        streamMember(chat.publicId.toString(), "끝을 낸다.", jwtTokenProvider.issueAccessToken(member.publicId))

        val reaches = userStoryEndingReachRepository.findByUserIdAndStoryId(member.id, story.id)
        assertThat(reaches.map { it.endingId }).containsExactly(happyEnding.id)
    }

    // ---- [KNK-1065 / PR #224 Codex P2] 저장 판정이 AI에게 보낸 것과 같은 출처를 본다 ----

    /** 공개 상태 저장 = 마지막 공개 버전 스냅샷 갱신. 비공개 전환 후 이 값이 AI 요청 재료가 된다. */
    private fun publish() {
        val loaded = storyRepository.findById(story.id).orElseThrow()
        snapshotService.refresh(loaded)
        storyRepository.save(loaded)
    }

    /**
     * 스토리를 소유자에게 귀속시키고 비공개로 내린다.
     *
     * **소유자를 붙이는 게 핵심이다.** `Story.isReadableBy`는 소유자가 없는(게스트 제작) 스토리를 누구에게나
     * 열어 주므로(§KNK-464), user_id가 NULL인 채로 비공개로 내리면 게이트가 닫히지 않아 조립이 계속 현재 값을
     * 본다. `Story.userId`가 val이라 SQL로 갱신한다.
     */
    private fun hideStoryFromReaders() {
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        jdbcTemplate.update(
            "UPDATE stories SET user_id = ?, status = 'DRAFT', visibility = 'PRIVATE' WHERE id = ?",
            owner.id,
            story.id,
        )
    }

    /** 수정 API의 endings[] 전체 교체와 같은 결과(행 삭제 후 재생성)를 만든다. */
    private fun replaceEndings(vararg names: String) {
        jdbcTemplate.update("DELETE FROM story_endings WHERE start_setting_id = ?", startSetting.id)
        names.forEachIndexed { index, name ->
            storyEndingRepository.save(
                StoryEnding(
                    startSetting = startSetting,
                    name = name,
                    minTurns = 1,
                    achievementCondition = "조건",
                    epilogue = "에필로그",
                    sortOrder = (index + 1).toShort(),
                ),
            )
        }
    }

    /** 수정 API의 mainEvents[] 전체 교체와 같은 결과. */
    private fun replaceMainEvents(vararg names: String) {
        jdbcTemplate.update("DELETE FROM story_chat_main_events")
        jdbcTemplate.update("DELETE FROM story_main_events WHERE story_id = ?", story.id)
        names.forEachIndexed { index, name ->
            storyMainEventRepository.save(
                StoryMainEvent(story = story, name = name, description = "설명", keySentence = "문장", sortOrder = index.toShort()),
            )
        }
    }

    @Test
    fun `비공개 전환 후 엔딩이 교체돼도 스냅샷 이름으로 도달을 기록한다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        hideStoryFromReaders()
        // 소유자가 감춘 채 엔딩을 통째로 갈았다. AI에게는 스냅샷의 옛 이름("해피")이 나가므로 AI도 그 이름을 돌려준다.
        replaceEndings("개작 엔딩")
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "마침내 평화가 찾아왔다.", choices = emptyList(), endingName = "해피")

        val body = streamGuest(chat.publicId.toString(), "최후의 일격을 가한다.")

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        // 예전에는 현재 story_endings에서 "해피"를 찾다 실패해 **도달이 통째로 누락**됐다.
        assertThat(updated.status).isEqualTo(ChatStatus.ENDED)
        assertThat(updated.reachedEndingNameSnapshot).isEqualTo("해피")
        // 현재 행에 그 이름이 없으므로 id는 남기지 못한다 — reached_ending_id는 story_endings FK다.
        assertThat(updated.reachedEndingId).isNull()
        assertThat(body).contains("\"해피\"")
    }

    @Test
    fun `엔딩 행이 교체돼도 이름이 같으면 도달 id까지 되찾는다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        hideStoryFromReaders()
        // 수정 API는 이름을 그대로 둬도 행을 지우고 새로 만든다. 이름으로 다시 찾으므로 id가 살아난다.
        replaceEndings("해피")
        val recreated = storyEndingRepository.findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(startSetting.id).single()
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "평화.", choices = emptyList(), endingName = "해피")

        streamGuest(chat.publicId.toString(), "최후의 일격을 가한다.")

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(updated.reachedEndingId).isEqualTo(recreated.id)
        assertThat(updated.reachedEndingNameSnapshot).isEqualTo("해피")
    }

    @Test
    fun `AI가 요청에 없던 엔딩 이름을 보내면 도달로 인정하지 않는다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        hideStoryFromReaders()
        // 현재 행에는 있지만 **AI에게 보낸 스냅샷에는 없는** 이름이다. 후보 판정은 보낸 목록이 한다.
        replaceEndings("비공개 신작 엔딩")
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "끝.", choices = emptyList(), endingName = "비공개 신작 엔딩")

        streamGuest(chat.publicId.toString(), "일격.")

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(updated.status).isEqualTo(ChatStatus.ACTIVE)
        assertThat(updated.reachedEndingId).isNull()
        assertThat(updated.reachedEndingNameSnapshot).isNull()
    }

    @Test
    fun `비공개 전환 후 주요 사건 행이 교체돼도 이름이 같으면 완결 기록이 남는다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        hideStoryFromReaders()
        replaceMainEvents("발단", "절정")
        val recreated = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
        judgingAiClient.result = ChatTurnAiResult(
            aiOutput = "응답 본문",
            choices = listOf("선택 1"),
            targetMainEvent = ChatTurnTargetMainEventResult(name = "절정", progressTurns = 2),
            occurredMainEventName = "발단",
        )

        streamGuest(chat.publicId.toString(), "결전을 준비한다.")

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(updated.targetMainEventId).isEqualTo(recreated.first { it.name == "절정" }.id)
        assertThat(storyChatMainEventRepository.findByChatId(chat.id).map { it.mainEventId })
            .containsExactly(recreated.first { it.name == "발단" }.id)
    }

    @Test
    fun `비공개 전환 후 주요 사건 이름이 바뀌면 완결 기록을 남기지 않는다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        hideStoryFromReaders()
        replaceMainEvents("개작 발단", "개작 절정")
        judgingAiClient.result = ChatTurnAiResult(
            aiOutput = "응답 본문",
            choices = listOf("선택 1"),
            targetMainEvent = ChatTurnTargetMainEventResult(name = "절정", progressTurns = 2),
            occurredMainEventName = "발단",
        )

        streamGuest(chat.publicId.toString(), "결전을 준비한다.")

        // story_chat_main_events.main_event_id는 NOT NULL FK라 엔딩처럼 이름만 남길 자리가 없다.
        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(updated.targetMainEventId).isNull()
        assertThat(storyChatMainEventRepository.findByChatId(chat.id)).isEmpty()
    }

    @Test
    fun `AI가 요청에 없던 주요 사건 이름을 보내면 기록하지 않는다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        hideStoryFromReaders()
        // 현재 행에는 있지만 **AI에게 보낸 스냅샷에는 없는** 이름이다. 엔딩과 같은 규칙 —
        // 후보 판정은 보낸 목록이 하고, 현재 행 조회는 FK용 id를 얻는 데만 쓴다.
        replaceMainEvents("비공개 신작 사건")
        judgingAiClient.result = ChatTurnAiResult(
            aiOutput = "응답 본문",
            choices = listOf("선택 1"),
            targetMainEvent = ChatTurnTargetMainEventResult(name = "비공개 신작 사건", progressTurns = 2),
            occurredMainEventName = "비공개 신작 사건",
        )

        streamGuest(chat.publicId.toString(), "결전을 준비한다.")

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(updated.targetMainEventId).isNull()
        assertThat(storyChatMainEventRepository.findByChatId(chat.id)).isEmpty()
    }

    @Test
    fun `완결 사건 기록은 이름 스냅샷으로도 남는다`() {
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        judgingAiClient.result = ChatTurnAiResult(
            aiOutput = "응답 본문",
            choices = listOf("선택 1"),
            occurredMainEventName = "발단",
        )

        streamGuest(chat.publicId.toString(), "길을 나선다.")

        // 조인 행(정본)과 이름 스냅샷(복구용)을 둘 다 남긴다(PR #224 Codex P2).
        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(storyChatMainEventRepository.findByChatId(chat.id).map { it.mainEventId })
            .containsExactly(eventBaldan.id)
        assertThat(updated.occurredMainEventNamesSnapshot).containsExactly("발단")
    }

    @Test
    fun `주요 사건이 교체돼 완결 기록이 cascade 삭제돼도 다음 턴 요청에 완결 사건이 실린다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        // 1턴: '발단'을 완결한다.
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "응답", choices = listOf("선택 1"), occurredMainEventName = "발단")
        streamGuest(chat.publicId.toString(), "길을 나선다.")

        // 소유자가 감춘 채 주요 사건을 통째로 간다. story_chat_main_events.main_event_id는 CASCADE FK라
        // 조인 행이 함께 사라진다 — 완결 기록의 정본이 통째로 날아간다.
        hideStoryFromReaders()
        jdbcTemplate.update("DELETE FROM story_chat_main_events")
        jdbcTemplate.update("DELETE FROM story_main_events WHERE story_id = ?", story.id)
        assertThat(storyChatMainEventRepository.findByChatId(chat.id)).isEmpty()

        // 2턴: AI에게는 스냅샷의 옛 사건이 후보로 나가야 하고, '발단'은 **이미 완결했다**고 함께 나가야 한다.
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "응답", choices = listOf("선택 1"))
        streamGuest(chat.publicId.toString(), "다음 행동.")

        val captured = judgingAiClient.lastRequest.get() ?: error("AI 요청이 캡처되지 않았습니다.")
        assertThat(captured.mainEvents.map { it.name }).containsExactly("발단", "절정")
        // 이 값이 비면 독자가 이미 지난 사건을 다시 겪는다.
        assertThat(captured.occurredMainEventNames).containsExactly("발단")
    }

    @Test
    fun `공개 상태에서 사건 이름을 바꾸면 완결 표기도 현재 이름을 따라간다`() {
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "응답", choices = listOf("선택 1"), occurredMainEventName = "발단")
        streamGuest(chat.publicId.toString(), "길을 나선다.")

        // 공개 상태라 조인 행이 살아 있고, 이름만 바뀌었다. 이름 스냅샷("발단")이 아니라 현재 이름이 나가야 한다.
        jdbcTemplate.update("UPDATE story_main_events SET name = ? WHERE id = ?", "새 발단", eventBaldan.id)

        judgingAiClient.result = ChatTurnAiResult(aiOutput = "응답", choices = listOf("선택 1"))
        streamGuest(chat.publicId.toString(), "다음 행동.")

        val captured = judgingAiClient.lastRequest.get() ?: error("AI 요청이 캡처되지 않았습니다.")
        assertThat(captured.occurredMainEventNames).containsExactly("새 발단")
    }

    @Test
    fun `비공개 전환 후 사건 이름이 바뀌어도 새 완결은 이름 스냅샷으로 남고 다음 턴에 실린다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        hideStoryFromReaders()
        // 소유자가 감춘 채 사건 이름을 통째로 갈았다. AI에게는 스냅샷의 옛 이름('발단'·'절정')이 나간다.
        // 라이브 행에는 그 이름이 없으므로 조인 행을 만들 수 없다 — 그래도 완결 기록은 남아야 한다.
        replaceMainEvents("개작 발단", "개작 절정")
        judgingAiClient.result = ChatTurnAiResult(
            aiOutput = "응답", choices = listOf("선택 1"), occurredMainEventName = "발단",
        )

        streamGuest(chat.publicId.toString(), "길을 나선다.")

        val updated = storyChatRepository.findById(chat.id).orElseThrow()
        // 조인 행은 못 만든다(main_event_id는 NOT NULL FK인데 '발단' 행이 없다).
        assertThat(storyChatMainEventRepository.findByChatId(chat.id)).isEmpty()
        // 이름은 남는다. 이 기록이 없으면 독자가 이미 지난 사건을 다시 겪는다.
        assertThat(updated.occurredMainEventNamesSnapshot).containsExactly("발단")

        // 다음 턴 요청에 완결 목록으로 실린다.
        judgingAiClient.result = ChatTurnAiResult(aiOutput = "응답", choices = listOf("선택 1"))
        streamGuest(chat.publicId.toString(), "다음 행동.")

        val captured = judgingAiClient.lastRequest.get() ?: error("AI 요청이 캡처되지 않았습니다.")
        assertThat(captured.mainEvents.map { it.name }).containsExactly("발단", "절정")
        assertThat(captured.occurredMainEventNames).containsExactly("발단")
    }

    @Test
    fun `같은 사건을 두 번 완결로 보고해도 이름 스냅샷에 한 번만 남는다`() {
        publish()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, startSettingId = startSetting.id))
        hideStoryFromReaders()
        replaceMainEvents("개작 발단", "개작 절정")
        judgingAiClient.result = ChatTurnAiResult(
            aiOutput = "응답", choices = listOf("선택 1"), occurredMainEventName = "발단",
        )

        streamGuest(chat.publicId.toString(), "1턴.")
        streamGuest(chat.publicId.toString(), "2턴.")

        // 조인 행이 없어 유니크 제약이 막아주지 못하므로 이름 기준 중복 방지가 유일한 가드다.
        assertThat(storyChatRepository.findById(chat.id).orElseThrow().occurredMainEventNamesSnapshot)
            .containsExactly("발단")
    }

    private fun streamGuest(chatId: String, userInput: String): String =
        stream(chatId, userInput, authorization = null)

    private fun streamMember(chatId: String, userInput: String, token: String): String =
        stream(chatId, userInput, authorization = "Bearer $token")

    private fun stream(chatId: String, userInput: String, authorization: String?): String {
        val spec = restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
        authorization?.let { spec.header("Authorization", it) }
        return spec
            .body("""{"userInput":"$userInput"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")
    }
}
