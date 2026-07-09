package com.knk.manyak.global.observability.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `error_code` → 분석용 거친 `error_type`(network/validation/server) 매핑을 고정한다(스펙 §6-6-7).
 */
class AnalyticsErrorTypeTest {

    @Test
    fun `네트워크 계열 error_code는 network로 매핑된다`() {
        assertThat(AnalyticsErrorType.fromAiErrorCode("provider_timeout")).isEqualTo(AnalyticsErrorType.NETWORK)
        assertThat(AnalyticsErrorType.fromAiErrorCode("provider_unavailable")).isEqualTo(AnalyticsErrorType.NETWORK)
    }

    @Test
    fun `입력·응답 검증 계열 error_code는 validation으로 매핑된다`() {
        listOf(
            "provider_bad_request",
            "schema_validation_failed",
            "invalid_ai_response",
            "content_filter_blocked",
        ).forEach { code ->
            assertThat(AnalyticsErrorType.fromAiErrorCode(code)).isEqualTo(AnalyticsErrorType.VALIDATION)
        }
    }

    @Test
    fun `서버 내부 계열 error_code는 server로 매핑된다`() {
        assertThat(AnalyticsErrorType.fromAiErrorCode("provider_rate_limited")).isEqualTo(AnalyticsErrorType.SERVER)
        assertThat(AnalyticsErrorType.fromAiErrorCode("unexpected_error")).isEqualTo(AnalyticsErrorType.SERVER)
    }

    @Test
    fun `알 수 없거나 null인 error_code는 server로 폴백한다`() {
        assertThat(AnalyticsErrorType.fromAiErrorCode(null)).isEqualTo(AnalyticsErrorType.SERVER)
        assertThat(AnalyticsErrorType.fromAiErrorCode("AI_STREAM_FAILED")).isEqualTo(AnalyticsErrorType.SERVER)
        assertThat(AnalyticsErrorType.fromAiErrorCode("")).isEqualTo(AnalyticsErrorType.SERVER)
    }

    @Test
    fun `fromThrowable은 연결·timeout 예외를 network로, 그 외는 server로 본다`() {
        assertThat(AnalyticsErrorType.fromThrowable(java.util.concurrent.TimeoutException("t")))
            .isEqualTo(AnalyticsErrorType.NETWORK)
        assertThat(AnalyticsErrorType.fromThrowable(java.net.ConnectException("c")))
            .isEqualTo(AnalyticsErrorType.NETWORK)
        // 원인 체인에 timeout이 섞여 있어도 찾아낸다.
        assertThat(AnalyticsErrorType.fromThrowable(RuntimeException(java.util.concurrent.TimeoutException("t"))))
            .isEqualTo(AnalyticsErrorType.NETWORK)
        assertThat(AnalyticsErrorType.fromThrowable(IllegalStateException("boom")))
            .isEqualTo(AnalyticsErrorType.SERVER)
        assertThat(AnalyticsErrorType.fromThrowable(null)).isEqualTo(AnalyticsErrorType.SERVER)
    }

    @Test
    fun `wire 값은 소문자 network validation server다`() {
        assertThat(AnalyticsErrorType.NETWORK.wireValue).isEqualTo("network")
        assertThat(AnalyticsErrorType.VALIDATION.wireValue).isEqualTo("validation")
        assertThat(AnalyticsErrorType.SERVER.wireValue).isEqualTo("server")
    }
}
