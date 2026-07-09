package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.client.ChatTurnAiClient
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
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.UserStoryEndingReachRepository
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

        override fun streamTurn(request: ChatTurnAiRequest, onToken: (String) -> Unit): ChatTurnAiResult {
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
