package com.knk.manyak.admin.analytics

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper
import java.time.*

class MutableAnalyticsClock(@Volatile var at: Instant) : Clock() {
    override fun instant() = at
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
class AnalyticsTestFixture(val properties: AmplitudeDashboardProperties, val clock: MutableAnalyticsClock,
    val policy: AnalyticsPolicy = AnalyticsPolicy(), repository: AnalyticsValueRepository? = null, budget: AmplitudeBudget? = null) {
    val mapper = ObjectMapper()
    val store: AnalyticsValueRepository = repository ?: run {
        val ds = DriverManagerDataSource("jdbc:h2:mem:analytics-${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")
        val ddl = ClassPathResource("db/migration/V107__admin_analytics_values.sql").inputStream.bufferedReader().use { it.readText() }.replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
        ResourceDatabasePopulator(org.springframework.core.io.ByteArrayResource(ddl.toByteArray())).execute(ds)
        AnalyticsValueRepository(NamedParameterJdbcTemplate(ds), mapper, DataSourceTransactionManager(ds))
    }
    val budget = budget ?: AmplitudeBudget(clock, policy)
    val client = AmplitudeRestClient(properties, mapper, this.budget, policy)
    val snapshots = AnalyticsSnapshots(store, client, this.budget, mapper, clock, policy)
    val service = AdminAnalyticsService(snapshots, properties, mapper, clock)
}
