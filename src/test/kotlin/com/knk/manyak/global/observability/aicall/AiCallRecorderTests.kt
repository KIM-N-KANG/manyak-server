package com.knk.manyak.global.observability.aicall

import com.knk.manyak.global.observability.MdcKeys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * AiCallRecorder 적재 동작 검증. 실제 H2(@DataJpaTest)에 적재되는지 확인하고,
 * Recorder는 슬라이스에 없으므로 주입한 repository로 직접 생성한다.
 */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
class AiCallRecorderTests {

    @Autowired
    private lateinit var repository: AiCallLogRepository

    private lateinit var recorder: AiCallRecorder

    @BeforeEach
    fun setUp() {
        recorder = AiCallRecorder(repository, "manyak-server")
        // @SpringBootTest 통합 테스트가 같은 H2 인메모리 DB에 커밋한 행과 섞이지 않도록 비운다.
        repository.deleteAll()
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `성공 시 SUCCEEDED로 적재하고 결과와 log id를 반환한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_abc")
        MDC.put(MdcKeys.SESSION_ID, "sess_1")
        MDC.put(MdcKeys.DEVICE_ID_HASH, "device_hash_x")

        val recorded = recorder.record(
            AiCallContext(feature = AiCallFeature.STORYLINE_GENERATION, storyId = 5L),
        ) { "ai-result" }

        assertEquals("ai-result", recorded.result)
        val log = repository.findById(recorded.aiCallLogId).orElseThrow()
        assertEquals(AiCallStatus.SUCCEEDED, log.status)
        assertEquals("manyak-server", log.callerService)
        assertEquals("req_abc", log.requestId)
        assertEquals("sess_1", log.sessionId)
        assertEquals("device_hash_x", log.deviceIdHash)
        assertEquals(AiCallFeature.STORYLINE_GENERATION, log.feature)
        assertEquals(5L, log.storyId)
        assertNotNull(log.latencyMs)
        assertNotNull(log.completedAt)
        assertNull(log.errorCode)
    }

    @Test
    fun `실패 시 FAILED로 적재하고 예외를 그대로 전파한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_fail")

        val thrown = assertFailsWith<IllegalStateException> {
            recorder.record(
                AiCallContext(feature = AiCallFeature.CHAT_RESPONSE),
                errorCode = { "MY_CODE" },
            ) { throw IllegalStateException("boom") }
        }

        assertEquals("boom", thrown.message)
        val log = repository.findAll().single()
        assertEquals(AiCallStatus.FAILED, log.status)
        assertEquals("MY_CODE", log.errorCode)
        assertNotNull(log.latencyMs)
        assertNotNull(log.completedAt)
    }

    @Test
    fun `MDC가 unknown이면 session·device는 null로 정규화한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_x")
        MDC.put(MdcKeys.SESSION_ID, "unknown")
        MDC.put(MdcKeys.DEVICE_ID_HASH, "unknown")

        val recorded = recorder.record(AiCallContext(feature = AiCallFeature.CHAT_RESPONSE)) { 1 }

        val log = repository.findById(recorded.aiCallLogId).orElseThrow()
        assertEquals("req_x", log.requestId)
        assertNull(log.sessionId)
        assertNull(log.deviceIdHash)
    }

    @Test
    fun `request_id가 MDC에 없으면 unknown으로 적재한다`() {
        val recorded = recorder.record(AiCallContext(feature = AiCallFeature.CHAT_RESPONSE)) { 1 }

        val log = repository.findById(recorded.aiCallLogId).orElseThrow()
        assertEquals("unknown", log.requestId)
    }

    @Test
    fun `기본 errorCode는 예외 클래스 이름이다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")

        assertFailsWith<IllegalArgumentException> {
            recorder.record(AiCallContext(feature = AiCallFeature.STORY_COMPLETION)) {
                throw IllegalArgumentException("bad")
            }
        }

        val log = repository.findAll().single()
        assertEquals("IllegalArgumentException", log.errorCode)
    }

    @Test
    fun `컬럼 길이를 넘는 request_id는 잘라서 적재해 호출을 막지 않는다`() {
        val longRequestId = "req_" + "x".repeat(200)
        MDC.put(MdcKeys.REQUEST_ID, longRequestId)

        val recorded = recorder.record(AiCallContext(feature = AiCallFeature.CHAT_RESPONSE)) { 1 }

        val log = repository.findById(recorded.aiCallLogId).orElseThrow()
        assertEquals(128, log.requestId.length)
        assertEquals(longRequestId.take(128), log.requestId)
    }

    @Test
    fun `컬럼 길이를 넘는 error_code는 잘라서 적재해 원래 예외를 보존한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")
        val longCode = "E".repeat(200)

        val thrown = assertFailsWith<IllegalStateException> {
            recorder.record(
                AiCallContext(feature = AiCallFeature.CHAT_RESPONSE),
                errorCode = { longCode },
            ) { throw IllegalStateException("boom") }
        }

        assertEquals("boom", thrown.message)
        val log = repository.findAll().single()
        assertEquals(100, log.errorCode!!.length)
    }

    @Test
    fun `attachTurnNumber는 적재 후 turn_number를 채운다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")
        val recorded = recorder.record(AiCallContext(feature = AiCallFeature.CHAT_RESPONSE)) { 1 }
        assertNull(repository.findById(recorded.aiCallLogId).orElseThrow().turnNumber)

        recorder.attachTurnNumber(recorded.aiCallLogId, 3)

        assertEquals(3, repository.findById(recorded.aiCallLogId).orElseThrow().turnNumber)
    }

    @Test
    fun `실패 시 onFailure에 적재 id와 원래 예외를 전달한 뒤 전파한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")
        var capturedId: Long? = null
        var capturedMessage: String? = null

        assertFailsWith<IllegalStateException> {
            recorder.record(
                AiCallContext(feature = AiCallFeature.CHAT_RESPONSE),
                onFailure = { id, throwable ->
                    capturedId = id
                    capturedMessage = throwable.message
                },
            ) { throw IllegalStateException("boom") }
        }

        val log = repository.findAll().single()
        assertEquals(log.id, capturedId)
        assertEquals("boom", capturedMessage)
    }

    @Test
    fun `성공 시에는 onFailure를 호출하지 않는다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")
        var called = false

        recorder.record(
            AiCallContext(feature = AiCallFeature.CHAT_RESPONSE),
            onFailure = { _, _ -> called = true },
        ) { 1 }

        assertEquals(false, called)
    }

    @Test
    fun `meta 추출기가 성공 시 model·토큰·prompt_versions를 같은 저장에 반영한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")

        val recorded = recorder.record(
            AiCallContext(feature = AiCallFeature.STORY_COMPLETION),
            meta = {
                AiCallMeta(
                    model = "deepseek-v4-pro",
                    provider = "deepseek",
                    inputTokenCount = 6180,
                    outputTokenCount = 512,
                    retryCount = 1,
                    promptVersions = mapOf("COMPILE" to 2),
                )
            },
        ) { "ai-result" }

        val log = repository.findById(recorded.aiCallLogId).orElseThrow()
        assertEquals(AiCallStatus.SUCCEEDED, log.status)
        assertEquals("deepseek-v4-pro", log.model)
        assertEquals("deepseek", log.provider)
        assertEquals(6180, log.inputTokenCount)
        assertEquals(512, log.outputTokenCount)
        assertEquals(1, log.retryCount)
        assertEquals(mapOf("COMPILE" to 2), log.promptVersions)
    }

    @Test
    fun `meta 추출기가 null이면 meta 컬럼을 비워 둔다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")

        val recorded = recorder.record(
            AiCallContext(feature = AiCallFeature.CHAT_RESPONSE),
            meta = { null },
        ) { 1 }

        val log = repository.findById(recorded.aiCallLogId).orElseThrow()
        assertNull(log.model)
        assertNull(log.inputTokenCount)
        assertNull(log.promptVersions)
    }

    @Test
    fun `실패 시 meta 추출기를 호출하지 않고 FAILED로 적재한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")
        var metaCalled = false

        assertFailsWith<IllegalStateException> {
            recorder.record(
                AiCallContext(feature = AiCallFeature.CHAT_RESPONSE),
                meta = { metaCalled = true; null },
            ) { throw IllegalStateException("boom") }
        }

        assertEquals(false, metaCalled)
        val log = repository.findAll().single()
        assertEquals(AiCallStatus.FAILED, log.status)
        assertNull(log.model)
    }

    @Test
    fun `meta 추출이 던져도 성공한 AI 호출을 SUCCEEDED로 보존한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")

        val recorded = recorder.record(
            AiCallContext(feature = AiCallFeature.STORY_COMPLETION),
            meta = { throw IllegalStateException("meta boom") },
        ) { "ok" }

        assertEquals("ok", recorded.result)
        val log = repository.findById(recorded.aiCallLogId).orElseThrow()
        assertEquals(AiCallStatus.SUCCEEDED, log.status)
        assertNull(log.model)
    }

    @Test
    fun `컬럼 제약을 넘는 meta가 와도 성공한 AI 호출을 깨지 않고 잘라서 SUCCEEDED로 적재한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")

        val recorded = recorder.record(
            AiCallContext(feature = AiCallFeature.STORY_COMPLETION),
            meta = { AiCallMeta(model = "m".repeat(200), provider = "p".repeat(80), retryCount = -1) },
        ) { "ok" }

        assertEquals("ok", recorded.result)
        val log = repository.findById(recorded.aiCallLogId).orElseThrow()
        assertEquals(AiCallStatus.SUCCEEDED, log.status)
        assertEquals(100, log.model!!.length)
        assertEquals(40, log.provider!!.length)
        assertEquals(0, log.retryCount)
    }

    @Test
    fun `attachSentryEventId는 sentry_event_id를 채운다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req")
        val recorded = recorder.record(AiCallContext(feature = AiCallFeature.CHAT_RESPONSE)) { 1 }

        recorder.attachSentryEventId(recorded.aiCallLogId, "0123456789abcdef")

        assertEquals("0123456789abcdef", repository.findById(recorded.aiCallLogId).orElseThrow().sentryEventId)
    }
}
