package com.knk.manyak.global.observability.aicall

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.global.observability.StructuredLogger
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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * AI 호출이 실제 요청 흐름에서 ai_call_logs로 적재되고, 그 id가 ai_response_saved 로그에 연결되는지 검증한다.
 * chat_response는 chatSseExecutor 워커에서 일어나므로 request_id가 MDC로 전파돼 적재되는지도 함께 본다.
 * (실패 경로 적재와 feature별 전이는 AiCallRecorderTests에서 단위로 검증한다.)
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiCallLogRecordingIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var aiCallLogRepository: AiCallLogRepository

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `채팅 이어쓰기는 chat_response 호출을 식별자와 함께 SUCCEEDED로 적재하고 ai_response_saved에 연결한다`() {
        val story = seedStory()
        val session = storyChatRepository.save(StoryChat(storyId = story.id))

        val logger = LoggerFactory.getLogger(StructuredLogger::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            restTestClient.post()
                .uri("/api/v1/chats/${session.publicId}/turns/stream")
                .header("X-Manyak-Request-Id", "req_aicall_chat")
                .header("X-Manyak-Device-Id", "test-device")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body("""{"userInput":"마법수정에 손을 올린다."}""")
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java)
                .returnResult()

            val logs = aiCallLogRepository.findAll()
            // 턴 흐름은 본문(chat_response) 한 행만 적재한다. 선택지는 전용 엔드포인트로 분리돼(B23) 턴 스트림에서 호출되지 않는다.
            assertThat(logs).hasSize(1)
            val log = logs.single { it.feature == AiCallFeature.CHAT_RESPONSE }
            assertThat(logs.none { it.feature == AiCallFeature.CHOICE_GENERATION }).isTrue()
            assertThat(log.feature).isEqualTo(AiCallFeature.CHAT_RESPONSE)
            assertThat(log.status).isEqualTo(AiCallStatus.SUCCEEDED)
            assertThat(log.callerService).isEqualTo("manyak-server")
            assertThat(log.requestId).isEqualTo("req_aicall_chat")
            assertThat(log.storyId).isEqualTo(story.id)
            assertThat(log.chatId).isEqualTo(session.publicId)
            assertThat(log.turnNumber).isEqualTo(1)
            assertThat(log.latencyMs).isNotNull()
            assertThat(log.completedAt).isNotNull()
            // stub은 meta를 내려주지 않으므로, AI 응답 meta 컬럼은 비어 있어야 한다(실 AI 연동 전 회귀 가드).
            assertThat(log.model).isNull()
            assertThat(log.inputTokenCount).isNull()
            assertThat(log.outputTokenCount).isNull()
            assertThat(log.promptVersions).isNull()

            // ai_response_saved 로그에 ai_call_log_id가 실려 적재 행과 연결된다.
            val aiSaved = appender.list.filter { it.formattedMessage.contains("event_name=ai_response_saved") }
            assertThat(aiSaved).hasSize(1)
            assertThat(aiSaved.first().formattedMessage).contains("ai_call_log_id=${log.id}")
        } finally {
            logger.detachAppender(appender)
        }
    }

    private fun seedStory(): Story {
        val story = storyRepository.save(
            Story(title = "호아킨 아카데미의 무속성 신입생", genre = "판타지"),
        )
        storySettingRepository.save(
            StorySetting(
                story = story,
                worldSetting = "마법 아카데미가 존재하는 세계",
                characterSetting = "강진우, 무속성 판정을 받은 1학년",
                userRoleSetting = "무속성 신입생",
                ruleSetting = "마법은 속성 발현으로만 사용한다.",
            ),
        )
        storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "강진우",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사가 시작된다.",
            ),
        )
        return story
    }
}
