package com.knk.manyak.global.observability.analytics

/**
 * Amplitude device_id와 앱 버전 전용 요청 문맥. 기기 원본은 MDC·Sentry·저장소에 넣지 않는다.
 * 요청 필터와 MdcTaskDecorator가 범위를 열고 닫으며, 발행 시 이벤트에 값만 복사한다.
 * 앱 버전은 Android 앱이 모든 요청에 싣는 `X-Manyak-App-Version` 헤더 값이고 웹은 보내지 않는다(ACCOUNT 결정 20).
 */
internal object AnalyticsDeviceContext {
    data class Values(val deviceId: String?, val appVersion: String?)
    private val current = ThreadLocal<Values>()

    fun currentDeviceId(): String? = current.get()?.deviceId
    fun currentAppVersion(): String? = current.get()?.appVersion
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
