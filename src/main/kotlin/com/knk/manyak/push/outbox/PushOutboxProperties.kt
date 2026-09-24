package com.knk.manyak.push.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 배치 전송 제한보다 임대가 길어야 아직 발행 중인 행을 다른 릴레이가 가져가지 않는다.
 * 브로커 클라이언트 자체 제한은 어댑터에서 이 배치 제한보다 짧게 검증한다.
 */
@ConfigurationProperties("manyak.push.outbox")
data class PushOutboxProperties(
    val pollInterval: Duration = Duration.ofSeconds(2),
    val batchSize: Int = 100,
    val lease: Duration = Duration.ofSeconds(60),
    val giveUpAfter: Duration = Duration.ofHours(24),
    val backoffInitial: Duration = Duration.ofSeconds(5),
    val backoffMax: Duration = Duration.ofMinutes(5),
    val sendTimeout: Duration = Duration.ofSeconds(20),
) {
    init {
        require(batchSize > 0)
        require(listOf(pollInterval, lease, giveUpAfter, backoffInitial, backoffMax, sendTimeout).all { !it.isNegative && !it.isZero })
        require(lease > sendTimeout) { "outbox lease must exceed send-timeout" }
        require(backoffMax >= backoffInitial)
    }
}
