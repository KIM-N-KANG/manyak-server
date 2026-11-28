package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.dto.SocialProvider
import com.knk.manyak.global.observability.*
import com.knk.manyak.global.observability.sentry.SentryMdcEventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.protocol.Request
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    }) { null }
    private val publisher = AmplitudeAnalyticsEventPublisher(false, "", "https://example.invalid")

    private fun request(deviceId: String? = raw) = MockHttpServletRequest("POST", "/api/v1/auth/social/google").apply {
        deviceId?.let { addHeader(RequestCorrelationFilter.HEADER_DEVICE_ID, it) }
        addHeader(RequestCorrelationFilter.HEADER_SESSION_ID, "123")
    }

    private fun emit(member: Boolean) {
        if (member) analytics.socialLoginSucceeded("google", "member-public-id", false)
        else analytics.socialLoginFailed("google", AnalyticsErrorType.VALIDATION)
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
            }) { null }
            filter.doFilter(request(), MockHttpServletResponse(), FilterChain { _, _ ->
                failing.socialLoginFailed("google", AnalyticsErrorType.SERVER)
            })
            assertThat(appender.list).isNotEmpty()
            assertThat(appender.list.map { it.formattedMessage }).allSatisfy { assertThat(it).doesNotContain(raw) }
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @Test
    fun `요청 플랫폼을 로그인 성공 이벤트의 Amplitude 기기 필드에 싣고 판정하지 못하면 생략한다`() {
        // ACCOUNT 5-5 "요청 플랫폼": (X-Manyak-Platform, X-Manyak-App-Version) -> (platform, app_version), null은 생략
        val cases = listOf(
            Triple("ios", "1.0.0", "iOS" to "1.0.0"),
            Triple("web", null, "Web" to null),
            Triple(null, "1.4.0", "Android" to "1.4.0"),
            Triple("desktop", null, null to null),
            Triple(null, null, null to null),
            Triple("android", "1".repeat(51), "Android" to null),
            Triple("desktop", "1.4.0", "Android" to "1.4.0"),
        )
        for ((platform, appVersion, expected) in cases) {
            val request = request().apply {
                platform?.let { addHeader(RequestCorrelationFilter.HEADER_PLATFORM, it) }
                appVersion?.let { addHeader(RequestCorrelationFilter.HEADER_APP_VERSION, it) }
            }
            filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> emit(true) })
            val body = payload(events.last())
            assertThat(body["platform"] to body["app_version"]).isEqualTo(expected)
            if (expected.first == null) assertThat(body).doesNotContainKey("platform")
            if (expected.second == null) assertThat(body).doesNotContainKey("app_version")
            assertThat(events.last().eventProperties).doesNotContainKeys("platform", "app_version")
        }
        // 워커로 넘긴 작업도 같은 요청의 플랫폼과 앱 버전을 쓰고 요청이 끝나면 남지 않는다.
        val executor = Executors.newSingleThreadExecutor()
        try {
            lateinit var task: Runnable
            filter.doFilter(request().apply {
                addHeader(RequestCorrelationFilter.HEADER_PLATFORM, "ios")
                addHeader(RequestCorrelationFilter.HEADER_APP_VERSION, "2.0.0")
            }, MockHttpServletResponse(), FilterChain { _, _ ->
                task = MdcTaskDecorator().decorate { emit(false) }
            })
            executor.submit(task).get(5, TimeUnit.SECONDS)
            assertThat(payload(events.last())["platform"]).isEqualTo("iOS")
            assertThat(payload(events.last())["app_version"]).isEqualTo("2.0.0")
            emit(false)
            assertThat(payload(events.last())).doesNotContainKeys("platform", "app_version")
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `웹훅 이벤트는 요청 플랫폼과 앱 버전을 싣지 않는다`() {
        // PAYMENT 5-4-9: 웹훅은 헤더가 없어 생략한다. 헤더가 실려 와도 웹훅 경로는 싣지 않는다.
        val webhook = ServerAnalytics(object : AnalyticsEventPublisher {
            override fun publish(event: AnalyticsEvent) { events.add(event) }
        }) { java.util.UUID.randomUUID() }
        filter.doFilter(request().apply {
            addHeader(RequestCorrelationFilter.HEADER_PLATFORM, "ios")
            addHeader(RequestCorrelationFilter.HEADER_APP_VERSION, "1.0.0")
        }, MockHttpServletResponse(), FilterChain { _, _ -> webhook.paymentPurchaseSucceeded(1L, 1L, "product", 1000, 10) })
        assertThat(payload(events.single())).doesNotContainKeys("platform", "app_version", "device_id")
    }

    @Suppress("UNCHECKED_CAST")
    private fun payload(event: AnalyticsEvent) = (publisher.buildPayload(event)["events"] as List<Map<String, Any?>>).single()
}
