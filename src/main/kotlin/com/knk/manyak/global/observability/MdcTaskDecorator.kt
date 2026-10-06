package com.knk.manyak.global.observability

import io.micrometer.context.ContextSnapshotFactory
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

/**
 * 작업을 제출한 스레드의 MDC와 관측 스코프를 실행 스레드로 복사하는 TaskDecorator(AN-3 §3·§4, KNK-1552).
 *
 * 비동기 실행기(chatSseExecutor 등)에 적용하면 워커 스레드에서 찍는 로그·이벤트도
 * request_id/session_id/device_id_hash로 상관관계가 유지된다. 추적이 켜져 있으면 현재 스팬도 함께 넘어가
 * 워커가 만드는 AI 호출·DB 스팬이 요청 트레이스 아래에 붙는다. 실행 후에는 원래 컨텍스트로 되돌려
 * 스레드풀 재사용 시 값이 누수되지 않게 한다.
 */
class MdcTaskDecorator : TaskDecorator {
    // 관측 스코프만 복사한다. 등록된 다른 접근자(SecurityContext, Sentry 스코프)까지 워커로 넘기면 종전과 동작이 달라지고,
    // Sentry 접근자는 캡처만으로 제출 스레드의 MDC를 빈 맵으로 바꿔 놓는다(ModerationOutboxIntegrationTests로 확인).
    private val snapshots = ContextSnapshotFactory.builder()
        .captureKeyPredicate { it == ObservationThreadLocalAccessor.KEY }
        .build()

    override fun decorate(runnable: Runnable): Runnable {
        val captured = MDC.getCopyOfContextMap()
        val snapshot = snapshots.captureAll()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            if (captured != null) MDC.setContextMap(captured) else MDC.clear()
            try {
                snapshot.setThreadLocals().use { runnable.run() }
            } finally {
                if (previous != null) MDC.setContextMap(previous) else MDC.clear()
            }
        }
    }
}
