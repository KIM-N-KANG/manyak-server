package com.knk.manyak.support

import com.knk.manyak.chat.client.ChatCharacterImageEvent
import com.knk.manyak.chat.client.ChatChoicesResult
import com.knk.manyak.chat.client.ChatCompactAiClient
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.client.StubChatCompactAiClient
import com.knk.manyak.chat.client.StubChatTurnAiClient
import com.knk.manyak.feedback.event.FeedbackAsyncTestConfig
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.image.service.GeneratedImageStorage
import com.knk.manyak.image.service.S3GeneratedImageStorage
import com.knk.manyak.image.service.S3UploadedImageStorage
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.image.service.UploadedObject
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.client.StubStoryAiClient
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant

/**
 * AI 응답을 테스트가 정하는 조합(C, KNK-2053).
 *
 * 예전에는 클래스마다 중첩 `@TestConfiguration`으로 가짜 AI 빈을 달아 클래스 수만큼 컨텍스트가 생겼다. 이제 AI 클라이언트(턴·압축·스토리)와
 * 이미지 저장소(업로드·생성) 자리에는 위임 빈 하나만 두고, 각 테스트가 `@BeforeEach`에서 [ScriptedAi]에 자기 페이크를 꽂는다.
 * 아무것도 꽂지 않으면 메인 스텁과 실제 저장소로 위임하므로 기본 동작은 [IntegrationTest]와 같다. 꽂은 페이크는
 * 테스트마다 [ScriptedAi.reset]으로 원복된다.
 *
 * 피드백 비동기 알림을 기록하는 [FeedbackAsyncTestConfig]도 함께 둔다. 알림 대상을 하나 더할 뿐이라 다른 테스트에 중립이다.
 *
 * 턴 조립이 몇 행을 읽는지 재는 테스트가 있어 Hibernate 통계를 켠다. 통계 로거는 세션마다 INFO를 남기므로 WARN으로 낮춘다.
 */
@Import(ScriptedAiConfig::class, FeedbackAsyncTestConfig::class)
@TestPropertySource(
    properties = [
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN",
    ],
)
abstract class ScriptedAiIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    /**
     * `manyak.chat.turn.result`는 `model_code` 태그를 가진다. 이 조합에는 여러 모델로 턴을 만드는 클래스가 함께 있어,
     * 남겨 두면 `find(..).tag("outcome", ..).counter()`가 다른 모델의 카운터를 집는다(클래스마다 컨텍스트가 따로이던
     * 때는 생기지 않던 공유 상태). 서비스는 증가할 때마다 카운터를 다시 등록하므로 지워도 집계는 그대로 이어진다.
     */
    @BeforeEach
    fun forgetChatTurnResultMeters() {
        meterRegistry.find("manyak.chat.turn.result").meters().forEach { meterRegistry.remove(it) }
    }

    @AfterEach
    fun resetScriptedAi() = ScriptedAi.reset()
}

/** 테스트가 꽂는 페이크. null이면 [ScriptedAiConfig]의 위임 빈이 메인 스텁·실제 저장소를 쓴다. */
object ScriptedAi {
    @Volatile var chat: ChatTurnAiClient? = null
    @Volatile var story: StoryAiClient? = null
    @Volatile var imageStorage: UploadedImageStorage? = null
    @Volatile var compact: ChatCompactAiClient? = null
    @Volatile var generatedImageStorage: GeneratedImageStorage? = null

    fun reset() {
        generatedImageStorage = null
        compact = null
        chat = null
        story = null
        imageStorage = null
    }
}

@TestConfiguration
class ScriptedAiConfig {
    @Bean
    @Primary
    fun scriptedChatTurnAiClient(stub: StubChatTurnAiClient): ChatTurnAiClient = object : ChatTurnAiClient {
        private fun target() = ScriptedAi.chat ?: stub

        override fun streamTurn(
            request: ChatTurnAiRequest,
            traceLink: AiTraceLink,
            deadline: Instant?,
            onCharacterImage: (ChatCharacterImageEvent) -> Unit,
            onToken: (String) -> Unit,
        ): ChatTurnAiResult = target().streamTurn(request, traceLink, deadline, onCharacterImage, onToken)

        override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String, traceLink: AiTraceLink): ChatChoicesResult =
            target().generateChoices(request, aiOutput, traceLink)
    }

    @Bean
    @Primary
    fun scriptedChatCompactAiClient(stub: StubChatCompactAiClient): ChatCompactAiClient =
        ChatCompactAiClient { request, traceLink -> (ScriptedAi.compact ?: stub).compact(request, traceLink) }

    @Bean
    @Primary
    fun scriptedStoryAiClient(stub: StubStoryAiClient): StoryAiClient = object : StoryAiClient {
        private fun target() = ScriptedAi.story ?: stub

        override fun createStorylines(request: AiStorylinesRequest, traceLink: AiTraceLink): AiStorylinesResponse =
            target().createStorylines(request, traceLink)

        override fun compileStory(request: AiStoryCompileRequest, traceLink: AiTraceLink): AiStoryCompileResponse =
            target().compileStory(request, traceLink)
    }

    @Bean
    @Primary
    fun scriptedGeneratedImageStorage(real: S3GeneratedImageStorage): GeneratedImageStorage = object : GeneratedImageStorage {
        private fun target() = ScriptedAi.generatedImageStorage ?: real

        override fun upload(objectKey: String, bytes: ByteArray, contentType: String): String? =
            target().upload(objectKey, bytes, contentType)

        override fun delete(objectKey: String) = target().delete(objectKey)
    }

    @Bean
    @Primary
    fun scriptedUploadedImageStorage(real: S3UploadedImageStorage): UploadedImageStorage = object : UploadedImageStorage {
        private fun target() = ScriptedAi.imageStorage ?: real

        override fun presignPut(objectKey: String, contentType: String, contentLength: Long, expiresIn: Duration): String? =
            target().presignPut(objectKey, contentType, contentLength, expiresIn)

        override fun presignRealtimeImage(objectKey: String, expiresIn: Duration): String? =
            target().presignRealtimeImage(objectKey, expiresIn)

        override fun head(objectKey: String): UploadedObject? = target().head(objectKey)

        override fun copy(sourceKey: String, destinationKey: String) = target().copy(sourceKey, destinationKey)

        override fun isEnabled(): Boolean = target().isEnabled()

        override fun serveUrlOf(objectKey: String): String? = target().serveUrlOf(objectKey)
    }
}
