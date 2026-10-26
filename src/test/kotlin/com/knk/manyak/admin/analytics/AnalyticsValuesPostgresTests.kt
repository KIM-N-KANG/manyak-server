package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import com.knk.manyak.admin.entity.AdminAnalyticsDailyValue
import com.knk.manyak.admin.entity.AdminAnalyticsRangeValue
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
    private lateinit var ds: DriverManagerDataSource
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-22T03:00:00Z")
    private val date = LocalDate.parse("2026-10-20")
    private val definition = AnalyticsDefinition("pg_daily", "/api/2/users", listOf("m" to "active", "i" to "1"), 1, mapper)
    @BeforeAll fun start() {
        pg.start()
        ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "analytics-test")
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        store = AnalyticsValueRepository(NamedParameterJdbcTemplate(ds), mapper, DataSourceTransactionManager(ds))
    }
    @AfterAll fun stop() = pg.stop()
    private fun value(count: Int, fetched: Instant = now, finalized: Instant? = null) = AnalyticsValue(mapper.valueToTree(count), fetched, finalized)
    @ParameterizedTest
    @ValueSource(classes = [AdminAnalyticsDailyValue::class, AdminAnalyticsRangeValue::class])
    fun `Flyway 테이블과 Hibernate 엔티티의 타입을 validate로 검증한다`(entity: Class<*>) {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.connection.datasource" to ds,
            "hibernate.hbm2ddl.auto" to "validate",
            "hibernate.jdbc.time_zone" to "UTC",
        )).build()
        try {
            MetadataSources(registry).addAnnotatedClass(entity).buildMetadata().buildSessionFactory().use { factory ->
                assertThat(factory.isOpen).isTrue()
            }
        } finally {
            org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry)
        }
    }
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
    @Test fun `PostgreSQL 확정 해제는 읽은 행만 풀어 재수집 저장을 허용하고 새 확정 행은 보호한다`() {
        val def = AnalyticsDefinition("pg_retention_reopen", "/api/2/retention", emptyList(), 8, mapper)
        val old = value(1, now, now)
        store.saveDaily(def, mapOf(date to old))
        store.unfinalizeDaily(def, date, old)
        assertThat(store.daily(def, date, date).getValue(date).finalizedAt).isNull()
        val updated = value(2, now.plusSeconds(5), now.plusSeconds(5))
        store.saveDaily(def, mapOf(date to updated))
        store.unfinalizeDaily(def, date, old) // 다른 태스크가 받은 새 값은 풀지 않는다.
        store.unfinalizeDaily(def, date, updated.copy(finalizedAt = now)) // 받은 시각이 같아도 새 확정 시각을 보호한다.
        val row = store.daily(def, date, date).getValue(date)
        assertThat(row).isEqualTo(updated)
        store.saveDaily(def, mapOf(date to value(999, now.plusSeconds(10))))
        assertThat(store.daily(def, date, date).getValue(date)).isEqualTo(updated)
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
