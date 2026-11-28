package com.knk.manyak.global.observability.analytics

/**
 * Amplitude device_id 전용 요청 문맥. 기기 원본은 MDC·Sentry·저장소에 넣지 않는다.
 * 요청 필터와 MdcTaskDecorator가 범위를 열고 닫으며, 발행 시 이벤트에 값만 복사한다.
 * 플랫폼과 앱 버전은 원본이 아니라 MDC의 판정 값을 쓴다(ACCOUNT 결정 22).
 */
internal object AnalyticsDeviceContext {
    data class Values(val deviceId: String?)
    private val current = ThreadLocal<Values>()

    fun currentDeviceId(): String? = current.get()?.deviceId
    fun capture(): Values? = current.get()

    fun <T> with(values: Values?, action: () -> T): T {
        val previous = current.get()
        set(values)
        try {
            return action()
        } finally {
            set(previous)
        }
    }

    private fun set(value: Values?) {
        if (value == null) current.remove() else current.set(value)
    }
}
