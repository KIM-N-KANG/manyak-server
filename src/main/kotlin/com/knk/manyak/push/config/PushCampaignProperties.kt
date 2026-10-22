package com.knk.manyak.push.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("manyak.push.campaign")
data class PushCampaignProperties(
    val sendingTtl: Duration = Duration.ofMinutes(30),
) {
    init {
        require(!sendingTtl.isNegative && !sendingTtl.isZero) {
            "manyak.push.campaign.sending-ttl은 양수여야 합니다."
        }
    }
}
