package com.knk.manyak.global.observability.sentry

import com.knk.manyak.global.observability.MdcKeys
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.protocol.Request
import io.sentry.protocol.SentryTransaction
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URISyntaxException

/**
 * 모든 Sentry 이벤트에 MDC 상관관계 식별자를 부착한다.
 *
 * request_id는 검색·필터에 쓰이므로 tag로, 개인정보성 식별자(session_id·device_id_hash)는
 * context로 싣는다. MdcTaskDecorator가 비동기 워커까지 MDC를 전파하므로 chatSseExecutor에서
 * 캡처한 이벤트도 동일하게 채워진다.
 *
 * Sentry Spring Boot 통합이 ApplicationContext의 EventProcessor 빈을 자동으로 수집한다.
 */
@Component
class SentryMdcEventProcessor : EventProcessor {

    override fun process(event: SentryEvent, hint: Hint): SentryEvent {
        scrubRequest(event.request)
        mdc(MdcKeys.REQUEST_ID)?.let { event.setTag(MdcKeys.REQUEST_ID, it) }

        val identity = buildMap {
            mdc(MdcKeys.SESSION_ID)?.let { put(MdcKeys.SESSION_ID, it) }
            mdc(MdcKeys.DEVICE_ID_HASH)?.let { put(MdcKeys.DEVICE_ID_HASH, it) }
        }
        if (identity.isNotEmpty()) {
            event.contexts["identity"] = identity
        }
        return event
    }

    override fun process(transaction: SentryTransaction, hint: Hint): SentryTransaction {
        scrubRequest(transaction.request)
        return transaction
    }

    private fun scrubRequest(request: Request?) {
        if (request == null) return
        // 경로·URL 유효성과 무관하게 비밀값을 먼저 제거한다. SDK 기본 PII 목록에 의존하지 않는다.
        request.headers = request.headers?.filterKeys { !it.equals("X-Manyak-Internal-Secret", ignoreCase = true) }
        val url = request.url ?: return
        val path = try { URI(url).path } catch (_: URISyntaxException) { return } ?: return
        if (path !in AUTH_REQUEST_PATHS && !PROVIDER_AUTH_PATH.matches(path)) return
        // custom 헤더는 SDK의 기본 PII 제거 목록에 의존하지 않는다.
        request.data = null
        request.queryString = null
        request.cookies = null
        request.headers = emptyMap()
    }

    // "unknown"(헤더 누락 시 필터 기본값)은 노이즈이므로 부착하지 않는다.
    private fun mdc(key: String): String? = MDC.get(key)?.takeIf { it.isNotBlank() && it != UNKNOWN }

    private companion object {
        const val UNKNOWN = "unknown"
        // ACCOUNT 5-4-8: 신원 증명·코드·토큰이 오가는 경로의 요청 필드를 제거한다.
        // provider 경로는 검증 실패 요청도 보호하며, 추가 하위 경로와 일반 API는 매칭하지 않는다.
        val PROVIDER_AUTH_PATH = Regex("/api/v1/auth/(social|links)/[^/]+")
        val AUTH_REQUEST_PATHS = setOf(
            "/api/v1/auth/login/google",
            "/api/v1/admin/auth/login/google",
            "/api/v1/auth/login/kakao",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/logout",
        )
    }
}
