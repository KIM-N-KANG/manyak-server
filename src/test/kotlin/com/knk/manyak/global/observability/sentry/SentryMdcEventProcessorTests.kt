package com.knk.manyak.global.observability.sentry

import com.knk.manyak.global.observability.MdcKeys
import io.sentry.Hint
import io.sentry.SentryEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentryMdcEventProcessorTests {

    private val processor = SentryMdcEventProcessor()

    @AfterEach
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `소셜 인증 Sentry 요청에는 동의 코드와 ID 토큰 본문을 싣지 않는다`() {
        val request = io.sentry.protocol.Request().apply {
            url = "https://api.example.com/api/v1/auth/social/complete"
            headers = mapOf("X-Manyak-Consent-Token" to "secret-code", "Content-Type" to "application/json")
            data = "private-id-token"
            queryString = "private-query"
        }
        val event = SentryEvent().apply { this.request = request }
        processor.process(event, Hint())
        assertNull(event.request!!.data)
        assertNull(event.request!!.queryString)
        assertNull(event.request!!.headers?.get("X-Manyak-Consent-Token"))
    }

    @Test
    fun `request_id는 tag, session·device는 identity context로 부착한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_abc")
        MDC.put(MdcKeys.SESSION_ID, "sess_1")
        MDC.put(MdcKeys.DEVICE_ID_HASH, "device_hash_x")

        val event = processor.process(SentryEvent(), Hint())

        assertEquals("req_abc", event.getTag(MdcKeys.REQUEST_ID))
        @Suppress("UNCHECKED_CAST")
        val identity = event.contexts["identity"] as Map<String, Any>
        assertEquals("sess_1", identity[MdcKeys.SESSION_ID])
        assertEquals("device_hash_x", identity[MdcKeys.DEVICE_ID_HASH])
    }

    @Test
    fun `unknown 값과 미설정 키는 부착하지 않는다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_only")
        MDC.put(MdcKeys.SESSION_ID, "unknown")

        val event = processor.process(SentryEvent(), Hint())

        assertEquals("req_only", event.getTag(MdcKeys.REQUEST_ID))
        assertNull(event.contexts["identity"])
    }
}
