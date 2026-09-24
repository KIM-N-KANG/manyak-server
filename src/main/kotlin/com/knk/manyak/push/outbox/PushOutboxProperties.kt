package com.knk.manyak.push.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

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
