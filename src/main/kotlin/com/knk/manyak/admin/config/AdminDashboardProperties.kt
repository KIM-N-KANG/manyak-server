package com.knk.manyak.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("manyak.admin.dashboard")
data class AdminDashboardProperties(val excludedUserIds: List<Long> = listOf(1, 2, 3, 4, 5, 10, 11, 206, 207))

@Configuration
@EnableConfigurationProperties(AdminDashboardProperties::class)
class AdminDashboardConfig
