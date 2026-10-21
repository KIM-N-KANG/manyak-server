package com.knk.manyak.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("manyak.admin.token")
data class AdminTokenProperties(
    val audience: String = "manyak-admin",
    val ttl: Duration = Duration.ofHours(2),
)
