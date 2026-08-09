package com.knk.manyak.chat.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * `manyak.chat.turn.refund` 시계열을 기동 시 0으로 만들어 둔다(KNK-800).
 *
 * Micrometer Counter는 **첫 `increment()` 때 등록**되므로, 환불 실패가 한 번도 없으면
 * `outcome="failure"` 시계열 자체가 존재하지 않는다. 그러면 첫 실패가 **값 1인 샘플 하나로 등장**하는데,
 * `increase()`는 뺄 이전 샘플이 없어 결과를 내지 못한다. 알림 규칙은 그것을 No Data로 보고,
 * `noDataState: OK` 때문에 조용히 정상으로 처리한다.
 *
 * 하필 그 규칙(`grafana/alerts.yaml`의 "환불 실패")은 임계값이 0이다. **첫 한 건을 잡는 것이 목적인데
 * 정확히 그 한 건을 놓치는** 셈이고, 두 번째 실패가 나야 비로소 울린다. 환불 실패는 사용자가 실패한 턴에
 * 과금된 채 남았다는 뜻이라 한 건도 놓치면 안 된다.
 *
 * 0에서 시작하면 첫 실패가 0→1 증가로 잡히고, 배포 재시작에 따른 카운터 리셋도 Prometheus의 리셋 감지가
 * 정상 처리한다. 쿼리 쪽에서 우회하지 않은 이유는, 시계열이 갓 생긴 1분 창에서만 참이 되는 형태라
 * 평가 타이밍에 기대게 되거나 리셋 처리를 잃기 때문이다.
 *
 * `success`도 함께 등록한다. 실패율의 분모이자 대시보드 계열이라 없으면 같은 이유로 초반 해석이 흔들린다.
 *
 * Spring Boot가 [MeterBinder] 빈을 모든 레지스트리에 자동 적용한다.
 */
@Component
class ChatTurnRefundMeters : MeterBinder {

    override fun bindTo(registry: MeterRegistry) {
        listOf(ChatService.OUTCOME_SUCCESS, ChatService.OUTCOME_FAILURE).forEach { outcome ->
            Counter.builder(ChatService.METRIC_CHAT_TURN_REFUND)
                .description("채팅 턴 선차감 환불 결과")
                .tag("outcome", outcome)
                .register(registry)
        }
    }
}
