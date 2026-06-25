package com.knk.manyak.global.observability.sentry

import com.knk.manyak.global.observability.MdcKeys
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * 모든 Sentry 이벤트에 MDC 상관관계 식별자를 부착한다.
 *
 * request_id는 검색·필터에 쓰이므로 tag로, 개인정보성 식별자(session_id·anonymous_id_hash)는
 * context로 싣는다. MdcTaskDecorator가 비동기 워커까지 MDC를 전파하므로 chatSseExecutor에서
 * 캡처한 이벤트도 동일하게 채워진다.
 *
 * Sentry Spring Boot 통합이 ApplicationContext의 EventProcessor 빈을 자동으로 수집한다.
 */
@Component
class SentryMdcEventProcessor : EventProcessor {

    override fun process(event: SentryEvent, hint: Hint): SentryEvent {
        mdc(MdcKeys.REQUEST_ID)?.let { event.setTag(MdcKeys.REQUEST_ID, it) }

        val identity = buildMap {
            mdc(MdcKeys.SESSION_ID)?.let { put(MdcKeys.SESSION_ID, it) }
            mdc(MdcKeys.ANONYMOUS_ID_HASH)?.let { put(MdcKeys.ANONYMOUS_ID_HASH, it) }
        }
        if (identity.isNotEmpty()) {
            event.contexts["identity"] = identity
        }
        return event
    }

    // "unknown"(헤더 누락 시 필터 기본값)은 노이즈이므로 부착하지 않는다.
    private fun mdc(key: String): String? = MDC.get(key)?.takeIf { it.isNotBlank() && it != UNKNOWN }

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
