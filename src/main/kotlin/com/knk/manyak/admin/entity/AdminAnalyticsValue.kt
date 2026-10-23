package com.knk.manyak.admin.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

@MappedSuperclass
abstract class AdminAnalyticsValue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0
    @Column(nullable = false, length = 64) var metric: String = ""
    @Column(name = "params_hash", nullable = false, length = 64, columnDefinition = "char(64)") var paramsHash: String = ""
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var params: String = "{}"
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "\"value\"", nullable = false, columnDefinition = "jsonb") var value: String = "0"
    @Column(name = "fetched_at", nullable = false) var fetchedAt: Instant = Instant.EPOCH
    @Column(name = "finalized_at") var finalizedAt: Instant? = null
}
@Entity
@Table(name = "admin_analytics_daily_values", uniqueConstraints = [UniqueConstraint(name = "uk_admin_analytics_daily_values_key", columnNames = ["metric", "params_hash", "metric_date"])])
class AdminAnalyticsDailyValue : AdminAnalyticsValue() {
    @Column(name = "metric_date", nullable = false) var metricDate: LocalDate = LocalDate.EPOCH
}
@Entity
@Table(name = "admin_analytics_range_values", uniqueConstraints = [UniqueConstraint(name = "uk_admin_analytics_range_values_key", columnNames = ["metric", "params_hash", "range_from", "range_to"])])
class AdminAnalyticsRangeValue : AdminAnalyticsValue() {
    @Column(name = "range_from", nullable = false) var rangeFrom: LocalDate = LocalDate.EPOCH
    @Column(name = "range_to", nullable = false) var rangeTo: LocalDate = LocalDate.EPOCH
}
