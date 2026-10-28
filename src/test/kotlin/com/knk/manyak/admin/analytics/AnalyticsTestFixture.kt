package com.knk.manyak.admin.analytics

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper
import java.time.*

/** 배치 카탈로그가 읽는 chat_models 코드만 고정한다. */
fun modelCodeReads(vararg codes: String = arrayOf("AURORA", "LITE")) = object : AdminMetricReadRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())) {
    override fun modelCodes() = codes.toList()
}
class MutableAnalyticsClock(@Volatile var at: Instant) : Clock() {
    override fun instant() = at
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
/** 운영 실행기와 같은 2스레드 실행기에 거부 전환과 백그라운드 갱신 완료 대기를 더한다. */
class AwaitableRefreshExecutor : java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
    { task -> Thread(task, "test-analytics-refresh").apply { isDaemon = true } }) {
    @Volatile var rejecting = false
    private val running = java.util.concurrent.atomic.AtomicInteger()
    override fun execute(command: Runnable) {
        if (rejecting) throw java.util.concurrent.RejectedExecutionException("test")
        running.incrementAndGet()
        try { super.execute { try { command.run() } finally { running.decrementAndGet() } } }
        catch (failure: java.util.concurrent.RejectedExecutionException) { running.decrementAndGet(); throw failure }
    }
    fun await() {
        val until = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos()
        while (running.get() > 0) { check(System.nanoTime() < until) { "background refresh did not finish" }; Thread.sleep(5) }
    }
}
class AnalyticsTestFixture(val properties: AmplitudeDashboardProperties, val clock: MutableAnalyticsClock,
    val policy: AnalyticsPolicy = AnalyticsPolicy(), repository: AnalyticsValueRepository? = null, budget: AmplitudeBudget? = null,
    val executor: AwaitableRefreshExecutor = AwaitableRefreshExecutor()) {
    val mapper = ObjectMapper()
    val store: AnalyticsValueRepository = repository ?: run {
        val ds = DriverManagerDataSource("jdbc:h2:mem:analytics-${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")
        val ddl = ClassPathResource("db/migration/V107__admin_analytics_values.sql").inputStream.bufferedReader().use { it.readText() }.replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
        ResourceDatabasePopulator(org.springframework.core.io.ByteArrayResource(ddl.toByteArray())).execute(ds)
        AnalyticsValueRepository(NamedParameterJdbcTemplate(ds), mapper, DataSourceTransactionManager(ds))
    }
    val budget = budget ?: AmplitudeBudget(clock, policy)
    val client = AmplitudeRestClient(properties, mapper, this.budget, policy)
    val snapshots = AnalyticsSnapshots(store, client, this.budget, mapper, clock, policy, executor)
    val service = AdminAnalyticsService(snapshots, properties, mapper, clock)
}
