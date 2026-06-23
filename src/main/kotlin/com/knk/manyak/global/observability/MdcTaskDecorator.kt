package com.knk.manyak.global.observability

import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

/**
 * 작업을 제출한 스레드의 MDC를 실행 스레드로 복사하는 TaskDecorator(AN-3 §3·§4).
 *
 * 비동기 실행기(chatSseExecutor 등)에 적용하면 워커 스레드에서 찍는 로그·이벤트도
 * request_id/session_id/anonymous_id_hash로 상관관계가 유지된다. 실행 후에는 원래 컨텍스트로 되돌려
 * 스레드풀 재사용 시 값이 누수되지 않게 한다.
 */
class MdcTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val captured = MDC.getCopyOfContextMap()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            if (captured != null) MDC.setContextMap(captured) else MDC.clear()
            try {
                runnable.run()
            } finally {
                if (previous != null) MDC.setContextMap(previous) else MDC.clear()
            }
        }
    }
}
