package com.knk.manyak.global.observability.analytics

/**
 * 서버 분석 이벤트의 `error_type`(스펙 §6-6-7). 사용자·퍼널 분석용 거친 분류이며 network/validation/server 3종만 쓴다.
 * 내부 상세 실패 코드(`ai_call_logs.error_code`)는 [fromAiErrorCode]로 이 3종에 매핑한다.
 */
enum class AnalyticsErrorType(val wireValue: String) {
    NETWORK("network"),
    VALIDATION("validation"),
    SERVER("server"),
    ;

    companion object {
        // 스펙 §6-6-7 매핑 표. 여기 없는(또는 null) 코드는 내부 처리 실패로 보아 server로 폴백한다.
        private val BY_AI_ERROR_CODE: Map<String, AnalyticsErrorType> = mapOf(
            "provider_timeout" to NETWORK,
            "provider_unavailable" to NETWORK,
            "provider_bad_request" to VALIDATION,
            "schema_validation_failed" to VALIDATION,
            "invalid_ai_response" to VALIDATION,
            "content_filter_blocked" to VALIDATION,
            "provider_rate_limited" to SERVER,
            "unexpected_error" to SERVER,
        )

        /** 내부 AI 실패 코드를 분석용 [AnalyticsErrorType]으로 매핑한다. 미지·null·공백은 server. */
        fun fromAiErrorCode(errorCode: String?): AnalyticsErrorType =
            errorCode?.let { BY_AI_ERROR_CODE[it] } ?: SERVER

        /**
         * 세분화된 error_code 없이 예외만 있을 때(예: AI 호출 예외) 거친 분류를 추정한다. 예외 체인에 연결·timeout류가
         * 보이면 network, 그 외는 server로 본다(스펙 §6-6-7 — 정확한 code가 없으면 내부 처리 실패로 폴백).
         */
        fun fromThrowable(throwable: Throwable?): AnalyticsErrorType {
            var cause: Throwable? = throwable
            val seen = HashSet<Throwable>()
            while (cause != null && seen.add(cause)) {
                val name = cause.javaClass.simpleName
                if (name.contains("Timeout", ignoreCase = true) ||
                    name.contains("Connect", ignoreCase = true) ||
                    name.contains("WebClientRequest", ignoreCase = true)
                ) {
                    return NETWORK
                }
                cause = cause.cause
            }
            return SERVER
        }
    }
}
