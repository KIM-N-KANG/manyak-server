package com.knk.manyak.global.observability.analytics

/**
 * Amplitude device_id 전용 요청 문맥. 원본은 MDC·Sentry·저장소에 넣지 않는다.
 * 요청 필터와 MdcTaskDecorator가 범위를 열고 닫으며, 발행 시 이벤트에 값만 복사한다.
 */
internal object AnalyticsDeviceContext {
    private val deviceId = ThreadLocal<String>()

    fun currentDeviceId(): String? = deviceId.get()

    fun <T> withDeviceId(value: String?, action: () -> T): T {
        val previous = deviceId.get()
        set(value)
        try {
            return action()
        } finally {
            set(previous)
        }
    }

    private fun set(value: String?) {
        if (value == null) deviceId.remove() else deviceId.set(value)
    }
}
