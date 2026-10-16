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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = [
        "/api/v1/auth/social/google", "/api/v1/auth/social/kakao", "/api/v1/auth/social/complete",
        "/api/v1/auth/login/google", "/api/v1/auth/login/kakao",
        "/api/v1/auth/token/refresh", "/api/v1/auth/logout",
        "/api/v1/auth/handoffs", "/api/v1/auth/handoffs/status",
        "/api/v1/auth/links/reauth", "/api/v1/auth/links/google", "/api/v1/auth/links/kakao",
        "/api/v1/auth/social/unknown", "/api/v1/auth/links/unknown",
        "/api/v1/auth/links/KaKaO",
    ])
    fun `스펙 5-4-8 인증 경로의 event와 transaction에서 요청 필드 4종을 제거한다`(path: String) {
        for (url in listOf("https://api.example.com$path?source=test", path)) {
            assertRequestPolicy(url, scrub = true)
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = ["GET", "POST"])
    fun `핸드오프는 GET과 메서드 누락만 정제하고 POST는 event와 transaction 모두 보존한다`(method: String?) {
        for (path in listOf("/api/v1/auth/handoffs", "/api/v1/auth/handoffs/status")) {
            for (url in listOf(path, "https://api.example.com$path")) {
                assertRequestPolicy(url, scrub = method == null || method == "GET", method = method)
            }
        }
    }

    @Test
    fun `스토리 채팅과 URL 일부만 일치하는 일반 요청의 관측 필드는 보존한다`() {
        for (url in listOf(
            "https://api.example.com/api/v1/stories",
            "https://api.example.com/api/v1/chats",
            "https://api.example.com/api/v1/auth/me",
            "https://api.example.com/api/v1/stories?next=/api/v1/auth/social/complete",
            "https://api.example.com/api/v1/auth/social/complete/extra",
            "https://api.example.com/api/v1/auth/links/kakao/extra",
            "https://api.example.com/prefix/api/v1/auth/social/google",
            "not a valid URI /api/v1/auth/social/complete",
        )) assertRequestPolicy(url, scrub = false)
    }

    private fun assertRequestPolicy(url: String, scrub: Boolean, method: String? = null) {
        fun request() = io.sentry.protocol.Request().apply {
            this.url = url
            this.method = method
            data = "test-body"
            headers = mapOf("X-Test" to "test-header")
            cookies = "test-cookie"
            queryString = "test-query"
        }
        MDC.put(MdcKeys.REQUEST_ID, "req-scope")
        MDC.put(MdcKeys.SESSION_ID, "session-scope")
        val cause = IllegalStateException("retained-exception")
        val event = SentryEvent(cause).apply { this.request = request(); setTag("existing", "keep") }
        kotlin.test.assertSame(event, processor.process(event, Hint()))
        kotlin.test.assertSame(cause, event.throwable)
        assertEquals("keep", event.getTag("existing"))
        assertEquals("req-scope", event.getTag(MdcKeys.REQUEST_ID))
        assertEquals(mapOf(MdcKeys.SESSION_ID to "session-scope"), event.contexts["identity"])
        val transaction = io.sentry.protocol.SentryTransaction(
            "test-transaction", 1.0, 2.0, emptyList(), emptyMap(), io.sentry.protocol.TransactionInfo("url"),
        ).apply {
            this.request = request()
            setTag(MdcKeys.REQUEST_ID, "existing-request")
            contexts["identity"] = mapOf("session_id" to "existing-session")
        }
        kotlin.test.assertSame(transaction, processor.process(transaction, Hint()))
        assertEquals("existing-request", transaction.getTag(MdcKeys.REQUEST_ID))
        assertEquals(mapOf("session_id" to "existing-session"), transaction.contexts["identity"])
        for (actual in listOf(event.request!!, transaction.request!!)) {
            assertEquals(url, actual.url)
            assertEquals(method, actual.method)
            assertEquals(if (scrub) null else "test-body", actual.data)
            assertEquals(if (scrub) null else "test-cookie", actual.cookies)
            assertEquals(if (scrub) null else "test-query", actual.queryString)
            assertEquals(if (scrub) emptyMap() else mapOf("X-Test" to "test-header"), actual.headers)
        }
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
