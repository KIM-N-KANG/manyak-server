package com.knk.manyak.push.delivery

import com.knk.manyak.push.dto.*
import com.knk.manyak.push.repository.PushDeliveryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** 앱 datasource와 별개인 폐기 가능한 PostgreSQL에서 V101과 실제 집계 SQL을 검증한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushDeliveryPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "delivery-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: PushDeliveryRepository
    @BeforeAll fun start() {
        pg.start()
        jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "delivery-test"))
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY); CREATE TABLE push_campaigns(id BIGINT PRIMARY KEY)")
        jdbc.execute(java.io.File("src/main/resources/db/migration/V101__create_push_delivery_results.sql").readText())
        repository = PushDeliveryRepository(jdbc)
    }
    @AfterAll fun stop() { pg.stop() }
    @BeforeEach fun clean() { jdbc.update("DELETE FROM push_delivery_results") }
    private fun report(id: String, at: String = "2026-10-20T15:00:00Z") = PushDeliveryReportRequest(id,
        PushDeliveryType.NOTICE, PushKind.SERVICE, UUID.randomUUID(), outcome = PushDeliveryOutcome.DELIVERED,
        reason = "OK", deliveredDevices = 2, failedDevices = 0, completedAt = Instant.parse(at))

    @Test fun `실제 PG 동시 삽입은 최초 결과만 남기고 KST 날짜 집계에 포함한다`() {
        jdbc.update("INSERT INTO push_campaigns(id) VALUES (1) ON CONFLICT DO NOTHING")
        val futures = (1..2).map { CompletableFuture.runAsync { repository.insert(report("same"), 1, null) } }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        repository.insert(report("same").copy(deliveredDevices = 9), 1, null)
        repository.insert(report("yesterday", "2026-10-20T14:59:59Z"), null, null)
        repository.insert(report("next", "2026-10-21T15:00:00Z"), null, null)
        val day = LocalDate.parse("2026-10-21")
        assertThat(repository.dailyStats(day, day, null)).containsExactly(PushDeliveryStatsItem(day,
            PushDeliveryType.NOTICE, PushKind.SERVICE, 1, 1, 2, 0))
        assertThat(repository.dailyStats(day, day, PushDeliveryType.PROMOTION)).isEmpty()
        assertThat(repository.campaignStats(1)).isEqualTo(CampaignDeliveryStats(1, 2, 0))
        assertThat(repository.campaignStats(99)).isEqualTo(CampaignDeliveryStats(0, 0, 0))
    }
}
