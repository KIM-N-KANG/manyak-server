package com.knk.manyak.global.observability

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * MDC에 적재된 상관관계 식별자를 AI 호출용 outbound 헤더로 변환하는 규칙을 고정한다.
 * 원본 PII는 MDC에 없으므로(해시만 존재) forward 대상에서 구조적으로 빠진다.
 */
class CorrelationHeadersTests {

    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `MDC의 세 식별자를 outbound 헤더로 변환한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_abc")
        MDC.put(MdcKeys.SESSION_ID, "sess_1")
        MDC.put(MdcKeys.ANONYMOUS_ID_HASH, "anon_hash_xyz")

        val headers = CorrelationHeaders.forwardingHeadersFromMdc()

        assertThat(headers).containsOnly(
            entry("X-Manyak-Request-Id", "req_abc"),
            entry("X-Manyak-Session-Id", "sess_1"),
            entry("X-Manyak-Anonymous-Id-Hash", "anon_hash_xyz"),
        )
    }

    @Test
    fun `unknown sentinel 값은 헤더에서 생략한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_abc")
        MDC.put(MdcKeys.SESSION_ID, RequestCorrelationFilter.UNKNOWN)
        MDC.put(MdcKeys.ANONYMOUS_ID_HASH, RequestCorrelationFilter.UNKNOWN)

        val headers = CorrelationHeaders.forwardingHeadersFromMdc()

        assertThat(headers).containsOnly(entry("X-Manyak-Request-Id", "req_abc"))
    }

    @Test
    fun `MDC에 값이 없으면 빈 헤더 맵을 만든다`() {
        val headers = CorrelationHeaders.forwardingHeadersFromMdc()

        assertThat(headers).isEmpty()
    }
}
