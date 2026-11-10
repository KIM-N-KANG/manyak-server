package com.knk.manyak.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties("manyak.admin.dashboard")
data class AdminDashboardProperties(
    val excludedUserIds: List<Long> = listOf(1, 2, 3, 4, 5, 10, 11, 206, 207),
    /** 지표 API가 Amplitude 플랫폼 값을 기다리는 최대 시간. 넘기면 받은 값만 저장하고 DB 값을 바로 응답한다. */
    val platformTimeout: Duration = Duration.ofSeconds(5),
)

@Configuration
@EnableConfigurationProperties(AdminDashboardProperties::class)
class AdminDashboardConfig
