package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.*
import com.knk.manyak.global.observability.sentry.SentryMdcEventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.protocol.Request
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RawDeviceIdAnalyticsTests {
    private val raw = "  client-device-1761  "
    private val hasher = DeviceIdHasher("test-pepper")
    private val filter = RequestCorrelationFilter(hasher)
    private val events = mutableListOf<AnalyticsEvent>()
    private val analytics = ServerAnalytics(object : AnalyticsEventPublisher {
        override fun publish(event: AnalyticsEvent) { events.add(event) }
    }, mock(UserRepository::class.java))
    private val publisher = AmplitudeAnalyticsEventPublisher(false, "", "https://example.invalid")

    private fun request(deviceId: String? = raw) = MockHttpServletRequest("POST", "/api/v1/auth/social/google").apply {
        deviceId?.let { addHeader(RequestCorrelationFilter.HEADER_DEVICE_ID, it) }
        addHeader(RequestCorrelationFilter.HEADER_SESSION_ID, "123")
    }

    private fun emit(member: Boolean) {
        if (member) analytics.socialLoginSucceeded(SocialProvider.GOOGLE, "member-public-id", false)
        else analytics.socialLoginFailed(SocialProvider.GOOGLE, AnalyticsErrorType.VALIDATION)
    }

    @Test
    fun `게스트와 회원의 device_id는 헤더 원본이고 내부 식별자는 해시다`() {
        for (member in listOf(false, true)) {
            filter.doFilter(request(), MockHttpServletResponse(), FilterChain { _, _ ->
                emit(member)
                assertThat(MDC.get(MdcKeys.DEVICE_ID_HASH)).isEqualTo(hasher.hash(raw))
                assertThat(MDC.getCopyOfContextMap().values).doesNotContain(raw)
                val sentry = SentryMdcEventProcessor().process(SentryEvent(), Hint())
                assertThat((sentry.contexts["identity"] as Map<*, *>)[MdcKeys.DEVICE_ID_HASH]).isEqualTo(hasher.hash(raw))
            })
            val event = events.last()
            assertThat(event.deviceId).isEqualTo(raw)
            assertThat(event.userId).isEqualTo(if (member) "member-public-id" else null)
            assertThat(event.eventProperties).containsEntry("device_id_hash", hasher.hash(raw))
            assertThat(event.eventProperties.values).doesNotContain(raw)
            assertThat(event.toString()).doesNotContain(raw)
            assertThat(payload(event)["device_id"]).isEqualTo(raw)
            assertThat(MDC.getCopyOfContextMap().orEmpty()).isEmpty()
        }
    }

    @Test
    fun `헤더가 없으면 게스트는 unknown 회원은 device_id와 해시를 생략한다`() {
        for (member in listOf(false, true)) {
            filter.doFilter(request(null), MockHttpServletResponse(), FilterChain { _, _ -> emit(member) })
            val event = events.last()
            assertThat(event.eventProperties).doesNotContainKey("device_id_hash")
            if (member) assertThat(payload(event)).doesNotContainKey("device_id")
            else assertThat(payload(event)["device_id"]).isEqualTo("unknown")
        }
    }

    @Test
    fun `내부 API의 전달 해시는 device_id 원본으로 사용하지 않는다`() {
        val request = MockHttpServletRequest("GET", "/internal/users/member/push-eligibility").apply {
            addHeader(CorrelationHeaders.HEADER_DEVICE_ID_HASH, hasher.hash(raw))
        }
        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> emit(false); emit(true) })
        assertThat(events.map { it.deviceId }).containsExactly("unknown", null)
        assertThat(events.map { it.eventProperties["device_id_hash"] }).containsOnly(hasher.hash(raw))
    }

    @Test
    fun `요청 종료 뒤 워커에서 생성한 이벤트도 원본을 사용하고 다음 작업에 누수되지 않는다`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            lateinit var task: Runnable
            filter.doFilter(request(), MockHttpServletResponse(), FilterChain { _, _ ->
                task = MdcTaskDecorator().decorate { emit(false) }
            })
            executor.submit(task).get(5, TimeUnit.SECONDS)
            assertThat(events.single().deviceId).isEqualTo(raw)
            executor.submit { emit(false) }.get(5, TimeUnit.SECONDS)
            assertThat(events.last().deviceId).isEqualTo("unknown")
            emit(false)
            assertThat(events.last().deviceId).isEqualTo("unknown")
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `필터 예외 종료도 원본을 정리한다`() {
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            filter.doFilter(request(), MockHttpServletResponse(), FilterChain { _, _ -> throw IllegalStateException("test") })
        }
        emit(false)
        assertThat(events.single().deviceId).isEqualTo("unknown")
    }

    @Test
    fun `일반 요청의 Sentry 이벤트와 트랜잭션도 원본 기기 헤더를 제거한다`() {
        fun sentryRequest() = Request().apply {
            url = "https://example.invalid/api/v1/stories"
            headers = mapOf("x-MaNyAk-DeViCe-Id" to raw, "Accept" to "application/json")
        }
        val processor = SentryMdcEventProcessor()
        val event = processor.process(SentryEvent().apply { request = sentryRequest() }, Hint())
        assertThat(event.request!!.headers).doesNotContainValue(raw).containsKey("Accept")
        val transaction = io.sentry.protocol.SentryTransaction(
            "test", 1.0, 2.0, emptyList(), emptyMap(), io.sentry.protocol.TransactionInfo("url"),
        ).apply { request = sentryRequest() }
        processor.process(transaction, Hint())
        assertThat(transaction.request!!.headers).doesNotContainValue(raw)
    }

    @Test
    fun `발행기 예외에 원본이 있어도 실패 로그에는 예외 타입만 남긴다`() {
        val logger = org.slf4j.LoggerFactory.getLogger(ServerAnalytics::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val failing = ServerAnalytics(object : AnalyticsEventPublisher {
                override fun publish(event: AnalyticsEvent) { throw IllegalStateException(raw) }
            }, mock(UserRepository::class.java))
            filter.doFilter(request(), MockHttpServletResponse(), FilterChain { _, _ ->
                failing.socialLoginFailed(SocialProvider.GOOGLE, AnalyticsErrorType.SERVER)
            })
            assertThat(appender.list).isNotEmpty()
            assertThat(appender.list.map { it.formattedMessage }).allSatisfy { assertThat(it).doesNotContain(raw) }
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun payload(event: AnalyticsEvent) = (publisher.buildPayload(event)["events"] as List<Map<String, Any?>>).single()
}
