package com.knk.manyak.push.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component

/**
 * 브로커 장애 때 배치 전송 제한까지 기다리는 릴레이를 다른 정기 작업과 분리한다.
 * 스케줄러를 빈으로 노출하지 않아 Boot의 기본 taskScheduler 자동 구성과 @Async 후보를 유지한다.
 * 단일 스레드 fixed delay로 같은 인스턴스의 poll이 겹치지 않으며, 컨텍스트 종료 시 함께 중단한다.
 */
@Component
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class PushOutboxScheduler(private val relay: PushOutboxRelay, private val settings: PushOutboxProperties) : SmartLifecycle {
    private val scheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("push-outbox-")
        setAwaitTerminationSeconds(5)
    }
    @Volatile private var running = false

    override fun start() {
        if (running) return
        scheduler.initialize()
        scheduler.scheduleWithFixedDelay({ relay.poll() }, settings.pollInterval)
        running = true
    }

    override fun stop() {
        // 기다리는 poll을 interrupt한다. 미확정 행은 임대 만료 후 다시 선점하므로 요청을 잃지 않는다.
        scheduler.shutdown()
        running = false
    }

    override fun isRunning(): Boolean = running
}
