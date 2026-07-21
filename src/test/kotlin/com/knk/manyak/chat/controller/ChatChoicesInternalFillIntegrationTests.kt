package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatChoicesResult
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallLogRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * [KNK-636 릴리스 언블록 stopgap] 검증. AI가 completed.choices를 빈 배열로 보내도(분리 후 현행), 백엔드가 턴 흐름에서
 * /chat/choices를 내부 호출해 completed·저장에 선택지를 채운다(프론트 무변경). 선택지 실패는 턴을 깨지 않는다.
 *
 * streamTurn이 빈 choices를 반환하는 페이크로 실 AI 상태를 흉내 낸다(기본 스텁은 streamTurn이 선택지를 실어 주므로 stopgap을 검증 못 함).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatChoicesInternalFillIntegrationTests {

    companion object {
        val genChoiceCalls = AtomicInteger(0)

        @Volatile
        var failChoices = false

        // streamTurn(=/chat/turns)이 실어 주는 choices. 분리 후 AI는 빈 배열이지만, 구 AI 계약·전환기 검증을 위해 토글한다.
        @Volatile
        var streamedChoices: List<String> = emptyList()

        val internalChoices = listOf("살핀다.", "나선다.", "벗어난다.")
    }

    @TestConfiguration
    class FakeAiClientConfig {
        @Bean
        @Primary
        fun fakeChatTurnAiClient(): ChatTurnAiClient = object : ChatTurnAiClient {
            // 분리 후 AI: 본문만 스트리밍하고 completed.choices는 빈 배열.
            override fun streamTurn(request: ChatTurnAiRequest, onToken: (String) -> Unit): ChatTurnAiResult {
                onToken("생성 ")
                return ChatTurnAiResult(aiOutput = "생성된 본문입니다.", choices = streamedChoices)
            }

            override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String): ChatChoicesResult {
                genChoiceCalls.incrementAndGet()
                if (failChoices) {
                    throw IllegalStateException("AI 선택지 강제 실패")
                }
                return ChatChoicesResult(choices = internalChoices)
            }
        }
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storySettingRepository: StorySettingRepository
    @Autowired private lateinit var storyStartSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var aiCallLogRepository: AiCallLogRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        genChoiceCalls.set(0)
        failChoices = false
        streamedChoices = emptyList()
        databaseCleaner.cleanAll()
    }

    @Test
    fun `AI가 빈 choices를 줘도 백엔드가 내부 호출로 completed·저장에 선택지를 채운다`() {
        val chat = seedChat()

        streamTurn(chat.publicId.toString())

        // 저장된 마지막 턴에 내부 호출로 채운 선택지가 실린다(프론트는 상세 조회로 그대로 렌더).
        val detail = getDetail(chat.publicId.toString())
        detail.jsonPath("$.turns.length()").isEqualTo(1)
            .jsonPath("$.turns[0].choices.length()").isEqualTo(3)
            .jsonPath("$.turns[0].choices[0]").isEqualTo("살핀다.")

        assertThat(genChoiceCalls.get()).isEqualTo(1)
        // ai_call_logs에 CHAT_RESPONSE + CHOICE_GENERATION 두 행이 같은 turn_number로 적재된다.
        val logs = aiCallLogRepository.findAll()
        assertThat(logs.count { it.feature == AiCallFeature.CHAT_RESPONSE }).isEqualTo(1)
        val choiceLog = logs.single { it.feature == AiCallFeature.CHOICE_GENERATION }
        assertThat(choiceLog.turnNumber).isEqualTo(1)
    }

    @Test
    fun `선택지 생성이 실패해도 턴은 저장되고 completed는 빈 choices로 발행된다`() {
        val chat = seedChat()
        failChoices = true

        streamTurn(chat.publicId.toString())

        // 본문 턴은 정상 저장되고, 선택지만 비어 있다(선택지 실패가 턴을 깨지 않음).
        getDetail(chat.publicId.toString())
            .jsonPath("$.turns.length()").isEqualTo(1)
            .jsonPath("$.turns[0].aiOutput").isEqualTo("생성된 본문입니다.")
            .jsonPath("$.turns[0].choices.length()").isEqualTo(0)
    }

    @Test
    fun `선택지 호출이 실패해도 스트리밍으로 받은 choices는 지우지 않는다`() {
        // Codex P1: 구 AI 계약(또는 전환기)에서 streamTurn이 이미 choices를 줬는데 새 /chat/choices 호출이 실패하면,
        // 그 유효한 choices를 빈 배열로 덮으면 안 된다.
        val chat = seedChat()
        streamedChoices = listOf("스트리밍 A", "스트리밍 B")
        failChoices = true

        streamTurn(chat.publicId.toString())

        getDetail(chat.publicId.toString())
            .jsonPath("$.turns[0].choices.length()").isEqualTo(2)
            .jsonPath("$.turns[0].choices[0]").isEqualTo("스트리밍 A")
    }

    private fun streamTurn(chatId: String) {
        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/stream")
            .header("X-Manyak-Device-Id", "internal-fill-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"마법수정에 손을 올렸지만 아무 빛도 나오지 않았다."}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
    }

    private fun getDetail(chatId: String) =
        restTestClient.get()
            .uri("/api/v1/chats/$chatId")
            .exchange()
            .expectStatus().isOk
            .expectBody()

    private fun seedChat(): StoryChat {
        val story = storyRepository.save(Story(title = "호아킨 아카데미", genre = "판타지"))
        storySettingRepository.save(
            StorySetting(
                story = story,
                worldSetting = "마법 아카데미가 존재하는 세계",
                characterSetting = "강진우, 무속성 1학년",
                userRoleSetting = "무속성 신입생",
                ruleSetting = "마법은 속성 발현으로만 사용한다.",
            ),
        )
        storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "강진우",
                prologue = "당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사가 시작된다.",
            ),
        )
        return storyChatRepository.save(StoryChat(storyId = story.id))
    }
}
