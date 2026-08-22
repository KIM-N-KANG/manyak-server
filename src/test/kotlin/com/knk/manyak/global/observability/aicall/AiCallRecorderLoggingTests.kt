package com.knk.manyak.global.observability.aicall

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.global.observability.StructuredLogger
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import net.logstash.logback.argument.StructuredArgument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertFailsWith

/**
 * AI 호출 구간 로그(KNK-960) 검증.
 *
 * KNK-855로 dev 로그가 OpenSearch에 모이면서 request_id 하나로 요청을 따라갈 수 있게 됐는데,
 * 서버 구간이 완료 한 줄뿐이라 전체 소요와 LLM 소요의 차이가 어디서 났는지 보이지 않았다.
 * 그래서 AI 호출을 감싸는 이 지점에서 시작·종료를 남긴다.
 */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
class AiCallRecorderLoggingTests {

    @Autowired
    private lateinit var repository: AiCallLogRepository

    private lateinit var recorder: AiCallRecorder
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var structuredLogger: Logger

    @BeforeEach
    fun setUp() {
        recorder = AiCallRecorder(repository, SimpleMeterRegistry(), "manyak-server", StructuredLogger())
        repository.deleteAll()
        structuredLogger = LoggerFactory.getLogger(StructuredLogger::class.java) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        structuredLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        structuredLogger.detachAppender(appender)
    }

    /** StructuredLogger가 실어 보낸 필드를 이름으로 꺼낸다. */
    private fun fieldsOf(event: ILoggingEvent): Map<String, Any?> {
        val argument = event.argumentArray?.firstOrNull() as? StructuredArgument ?: return emptyMap()
        // StructuredArguments.entries()는 toString()에서 `{k=v, k=v}` 형태로 펼쳐진다.
        return argument.toString()
            .trim('{', '}')
            .split(", ")
            .mapNotNull { it.split("=", limit = 2).takeIf { parts -> parts.size == 2 } }
            .associate { (k, v) -> k to v }
    }

    private fun eventsByName(): Map<String, Map<String, Any?>> =
        appender.list.associate { event ->
            val fields = fieldsOf(event)
            (fields["event_name"] as? String ?: "") to fields
        }

    @Test
    fun `AI 호출 성공이면 시작과 완료를 각각 남긴다`() {
        recorder.record(AiCallContext(AiCallFeature.STORYLINE_GENERATION)) { "ok" }

        val events = eventsByName()
        assertThat(events.keys).containsExactlyInAnyOrder("ai_call_started", "ai_call_completed")

        assertThat(events["ai_call_started"]!!["feature"]).isEqualTo("storyline_generation")
        val completed = events["ai_call_completed"]!!
        assertThat(completed["feature"]).isEqualTo("storyline_generation")
        assertThat(completed["outcome"]).isEqualTo("success")
        // 이 필드가 없으면 티켓의 목적(서버 구간이 어디서 시간을 썼는지)을 못 읽는다.
        assertThat(completed).containsKey("duration_ms")
        // ai_call_logs 행과 로그를 잇는 열쇠다.
        assertThat(completed).containsKey("ai_call_log_id")
    }

    @Test
    fun `AI 호출 실패여도 완료 로그를 남기고 예외는 그대로 전파한다`() {
        assertFailsWith<IllegalStateException> {
            recorder.record<String>(AiCallContext(AiCallFeature.CHAT_RESPONSE)) {
                throw IllegalStateException("AI 죽음")
            }
        }

        val completed = eventsByName()["ai_call_completed"]!!
        assertThat(completed["outcome"]).isEqualTo("failure")
        assertThat(completed["feature"]).isEqualTo("chat_response")
        assertThat(completed).containsKey("duration_ms")
    }

    @Test
    fun `프롬프트나 응답 본문은 로그에 남기지 않는다`() {
        val secret = "사용자가 쓴 채팅 원문"
        recorder.record(AiCallContext(AiCallFeature.CHAT_RESPONSE)) { secret }

        assertThat(appender.list.map { it.formattedMessage + fieldsOf(it).toString() })
            .noneMatch { it.contains(secret) }
    }

    @Test
    fun `로깅이 실패해도 AI 호출 결과를 바꾸지 않는다`() {
        val exploding = object : StructuredLogger() {
            override fun event(eventName: String, fields: Map<String, Any?>) {
                throw RuntimeException("로거 고장")
            }
        }
        val fragile = AiCallRecorder(repository, SimpleMeterRegistry(), "manyak-server", exploding)

        // 관측이 비즈니스를 깨면 안 된다. 메트릭·meta 추출과 같은 원칙이다.
        val recorded = fragile.record(AiCallContext(AiCallFeature.STORY_COMPLETION)) { "결과" }

        assertThat(recorded.result).isEqualTo("결과")
    }
}
