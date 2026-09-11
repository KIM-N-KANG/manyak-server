package com.knk.manyak.push.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * `manyak.push.send.result` 시계열을 기동 시 0으로 만들어 둔다(KNK-1130).
 *
 * Counter는 첫 `increment()` 때 등록되므로, 사전 등록이 없으면 첫 실패가 값 1인 샘플 하나로 등장해 `increase()`가
 * 잡지 못한다 — 임계값 0 알림이 정확히 첫 한 건을 놓치는 함정이다([com.knk.manyak.chat.service.ChatTurnRefundMeters]와
 * 같은 이유). 세 outcome을 전부 0에서 시작시킨다.
 */
@Component
class PushSendMeters : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        FcmPushSender.OUTCOMES.forEach { outcome ->
            Counter.builder(FcmPushSender.METRIC_PUSH_SEND_RESULT)
                .description("FCM 푸시 발송 결과")
                .tag("outcome", outcome)
                .register(registry)
        }
    }
}
