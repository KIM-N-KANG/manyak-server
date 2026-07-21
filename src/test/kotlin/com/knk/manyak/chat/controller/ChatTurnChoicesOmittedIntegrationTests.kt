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
 * B23(스펙 §4-3-3): 채팅 턴 스트림은 선택지를 생성·저장하지 않는다. `completed.choices`는 항상 빈 배열이고 story_choices도
 * 빈 상태로 시작하며, 선택지는 프론트가 전용 엔드포인트(POST /turns/{turnId}/choices)로 채운다(KNK-625 분리). 과도기 배선
 * (KNK-636 stopgap: 턴 흐름에서 /chat/choices 내부 호출)이 제거됐음을 회귀 가드한다.
 *
 * 페이크 AI는 generateChoices 호출 횟수를 센다 — 턴 스트림이 이를 부르지 않아야 한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatTurnChoicesOmittedIntegrationTests {

    companion object {
        val genChoiceCalls = AtomicInteger(0)

        // streamTurn(=/chat/turns)이 실어 주는 choices. 분리 후 AI는 빈 배열이지만, 잔여 값이 와도 턴 스트림이 비우는지 검증하려 토글한다.
        @Volatile
        var streamedChoices: List<String> = emptyList()
    }

    @TestConfiguration
    class FakeAiClientConfig {
        @Bean
        @Primary
        fun fakeChatTurnAiClient(): ChatTurnAiClient = object : ChatTurnAiClient {
            override fun streamTurn(request: ChatTurnAiRequest, onToken: (String) -> Unit): ChatTurnAiResult {
                onToken("생성 ")
                return ChatTurnAiResult(aiOutput = "생성된 본문입니다.", choices = streamedChoices)
            }

            override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String): ChatChoicesResult {
                genChoiceCalls.incrementAndGet()
                return ChatChoicesResult(choices = listOf("살핀다.", "나선다."))
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
        streamedChoices = emptyList()
        databaseCleaner.cleanAll()
    }

    @Test
    fun `턴 스트림은 선택지를 내부 생성하지 않고 completed·저장 choices는 빈 배열이다`() {
        val chat = seedChat()

        streamTurn(chat.publicId.toString())

        // 저장된 마지막 턴의 choices는 비어 있다(전용 엔드포인트가 채울 몫).
        getDetail(chat.publicId.toString())
            .jsonPath("$.turns.length()").isEqualTo(1)
            .jsonPath("$.turns[0].aiOutput").isEqualTo("생성된 본문입니다.")
            .jsonPath("$.turns[0].choices.length()").isEqualTo(0)

        // 턴 스트림은 선택지 AI를 호출하지 않는다.
        assertThat(genChoiceCalls.get()).isZero()
        // ai_call_logs에는 CHAT_RESPONSE만 있고 CHOICE_GENERATION은 없다(내부 선택지 호출 제거).
        val logs = aiCallLogRepository.findAll()
        assertThat(logs.count { it.feature == AiCallFeature.CHAT_RESPONSE }).isEqualTo(1)
        assertThat(logs.none { it.feature == AiCallFeature.CHOICE_GENERATION }).isTrue()
    }

    @Test
    fun `streamTurn이 choices를 실어 줘도 턴 스트림은 빈 배열로 확정한다`() {
        // 계약을 확정적으로 유지: 잔여·구 계약으로 streamTurn 결과에 choices가 있어도 턴 저장·completed는 항상 빈 배열이다.
        val chat = seedChat()
        streamedChoices = listOf("잔여 A", "잔여 B")

        streamTurn(chat.publicId.toString())

        getDetail(chat.publicId.toString())
            .jsonPath("$.turns[0].choices.length()").isEqualTo(0)
        assertThat(genChoiceCalls.get()).isZero()
    }

    private fun streamTurn(chatId: String) {
        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/stream")
            .header("X-Manyak-Device-Id", "choices-omitted-device")
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
