package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.databind.ObjectMapper
import java.time.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsValuesPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "analytics-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var store: AnalyticsValueRepository
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-22T03:00:00Z")
    private val date = LocalDate.parse("2026-10-20")
    private val definition = AnalyticsDefinition("pg_daily", "/api/2/users", listOf("m" to "active", "i" to "1"), 1, mapper)
    @BeforeAll fun start() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "analytics-test")
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        store = AnalyticsValueRepository(NamedParameterJdbcTemplate(ds), mapper, DataSourceTransactionManager(ds))
    }
    @AfterAll fun stop() = pg.stop()
    private fun value(count: Int, fetched: Instant = now, finalized: Instant? = null) = AnalyticsValue(mapper.valueToTree(count), fetched, finalized)
    @Test fun `PostgreSQL 일별 upsert는 미확정 행만 갱신하고 확정 값과 받은 시각을 보호한다`() {
        store.saveDaily(definition, mapOf(date to value(1)))
        store.saveDaily(definition, mapOf(date to value(2, now.plusSeconds(5), now.plusSeconds(5))))
        store.saveDaily(definition, mapOf(date to value(999, now.plusSeconds(10))))
        val row = store.daily(definition, date, date).getValue(date)
        assertThat(row.value.asLong()).isEqualTo(2)
        assertThat(row.fetchedAt).isEqualTo(now.plusSeconds(5)); assertThat(row.finalizedAt).isEqualTo(now.plusSeconds(5))
        store.saveDaily(definition, mapOf(date.plusDays(1) to value(3)))
        assertThat(store.daily(definition, date, date.plusDays(1))).hasSize(2)
    }
    @Test fun `PostgreSQL 범위 upsert도 확정 행 보호 조건을 원자적으로 적용한다`() {
        val from = date.minusDays(2)
        store.saveRange(definition, from, date, value(4))
        store.saveRange(definition, from, date, value(5, now.plusSeconds(5), now.plusSeconds(5)))
        store.saveRange(definition, from, date, value(999, now.plusSeconds(10)))
        val row = store.range(definition, from, date)!!
        assertThat(row.value.asLong()).isEqualTo(5)
        assertThat(row.fetchedAt).isEqualTo(now.plusSeconds(5)); assertThat(row.finalizedAt).isEqualTo(now.plusSeconds(5))
        assertThat(store.range(definition, from.minusDays(1), date)).isNull()
    }
}
