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
    fun `request_id는 tag, session·anonymous는 identity context로 부착한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_abc")
        MDC.put(MdcKeys.SESSION_ID, "sess_1")
        MDC.put(MdcKeys.ANONYMOUS_ID_HASH, "anon_hash_x")

        val event = processor.process(SentryEvent(), Hint())

        assertEquals("req_abc", event.getTag(MdcKeys.REQUEST_ID))
        @Suppress("UNCHECKED_CAST")
        val identity = event.contexts["identity"] as Map<String, Any>
        assertEquals("sess_1", identity[MdcKeys.SESSION_ID])
        assertEquals("anon_hash_x", identity[MdcKeys.ANONYMOUS_ID_HASH])
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
