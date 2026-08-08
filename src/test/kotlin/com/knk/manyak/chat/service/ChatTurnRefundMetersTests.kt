package com.knk.manyak.chat.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 환불 카운터 사전 등록 검증(KNK-800). 알림이 첫 실패 한 건을 잡으려면 시계열이 그전부터 0으로 있어야
 * 한다 — 이유는 [ChatTurnRefundMeters] KDoc 참고. Spring 없이 레지스트리에 직접 바인딩해 확인한다.
 */
class ChatTurnRefundMetersTests {

    @Test
    fun `환불 카운터는 증가 이전에 success·failure 모두 0으로 등록된다`() {
        val registry = SimpleMeterRegistry()

        ChatTurnRefundMeters().bindTo(registry)

        listOf(ChatService.OUTCOME_SUCCESS, ChatService.OUTCOME_FAILURE).forEach { outcome ->
            val counter =
                registry.find(ChatService.METRIC_CHAT_TURN_REFUND).tag("outcome", outcome).counter()
            assertNotNull(counter, "$outcome 카운터가 등록되지 않았다")
            assertEquals(0.0, counter.count(), "$outcome 카운터는 0에서 시작해야 한다")
        }
    }

    @Test
    fun `사전 등록한 미터에 이어서 증가한다`() {
        // 미터 신원은 이름+태그다. 사전 등록과 ChatService.recordChatTurnRefund가 같은 상수를 쓰지 않으면
        // 시계열이 둘로 갈려 사전 등록이 무의미해진다. 같은 값으로 만든 Counter가 합쳐지는지 고정한다.
        val registry = SimpleMeterRegistry()
        ChatTurnRefundMeters().bindTo(registry)

        io.micrometer.core.instrument.Counter
            .builder(ChatService.METRIC_CHAT_TURN_REFUND)
            .tag("outcome", ChatService.OUTCOME_FAILURE)
            .register(registry)
            .increment()

        assertEquals(
            1,
            registry.find(ChatService.METRIC_CHAT_TURN_REFUND)
                .tag("outcome", ChatService.OUTCOME_FAILURE).counters().size,
            "같은 이름·태그인데 시계열이 갈렸다",
        )
        assertEquals(
            1.0,
            registry.find(ChatService.METRIC_CHAT_TURN_REFUND)
                .tag("outcome", ChatService.OUTCOME_FAILURE).counter()!!.count(),
        )
    }
}
