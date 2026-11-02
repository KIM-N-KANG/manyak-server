package com.knk.manyak.admin

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.repository.AdminCreditMetricsRepository
import com.knk.manyak.admin.service.AdminCreditMetricsService
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

class AdminCreditMetricsPostgresTests {
    @Test fun `실제 PostgreSQL에서 원장 사유와 유효 로트 및 탈퇴 팀 제외를 집계한다`() {
        val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "credit-metrics-test")
            .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
        pg.start()
        try {
            val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "credit-metrics-test")
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            val jdbc = JdbcTemplate(ds)
            val now = Instant.parse("2026-10-20T15:30:00Z")
            fun user(status: String = "ACTIVE") = jdbc.queryForObject(
                "INSERT INTO users(public_id,nickname,status) VALUES (?,?,?) RETURNING id",
                Long::class.java, UUID.randomUUID(), "이프 PG ${UUID.randomUUID()}", status)!!
            val a = user()
            user("SUSPENDED") // 로트가 없어도 0 구간에 포함한다.
            val deleted = user("DELETED")
            val marked = user()
            jdbc.update("UPDATE users SET deleted_at=? WHERE id=?", now.atOffset(ZoneOffset.UTC), marked)
            val team = user()
            fun transaction(owner: Long, amount: Long, reason: String, at: Instant = now) = jdbc.queryForObject(
                "INSERT INTO credit_transactions(user_id,amount,reason,created_at) VALUES (?,?,?,?) RETURNING id",
                Long::class.java, owner, amount, reason, at.atOffset(ZoneOffset.UTC))!!
            fun lot(owner: Long, original: Long, remaining: Long, reason: String?, expires: Instant?) {
                val tx = reason?.let { transaction(owner, original, it, now.minus(100, ChronoUnit.DAYS)) }
                jdbc.update("INSERT INTO credit_lots(user_id,transaction_id,original_amount,remaining,expires_at) VALUES (?,?,?,?,?)",
                    owner, tx, original, remaining, expires?.atOffset(ZoneOffset.UTC))
            }
            transaction(a, 1000, "PURCHASE")
            transaction(a, -650, "CHAT_TURN")
            transaction(a, -30, "EXPIRE")
            transaction(a, 20, "REFUND")
            transaction(team, 9999, "ADMIN_GRANT")
            lot(a, 1000, 300, "PURCHASE", now.plus(365, ChronoUnit.DAYS))
            lot(a, 250, 50, "REFUND", now.plus(30, ChronoUnit.DAYS))
            lot(a, 100, 100, null, null)
            lot(a, 40, 40, "ADMIN_GRANT", now.minusNanos(1000))
            lot(a, 20, 20, "SIGNUP_REWARD", now)
            lot(a, 10, 0, "ATTENDANCE_REWARD", null)
            for (owner in listOf(deleted, marked, team)) lot(owner, 9999, 9999, "PURCHASE", null)
            val service = AdminCreditMetricsService(AdminCreditMetricsRepository(NamedParameterJdbcTemplate(jdbc)),
                AdminDashboardProperties(listOf(team)), Clock.fixed(now, ZoneOffset.UTC))
            val result = service.metrics(AdminChartsRequest().apply { days = "7" })
            assertThat(result.granted).isEqualTo(1000)
            assertThat(result.spent).isEqualTo(650)
            assertThat(result.sinkRate).isEqualTo(0.65)
            assertThat(result.grantedByReason.map { it.amount }).containsExactly(0, 0, 0, 1000, 0)
            assertThat(result.spentByReason.map { it.amount }).containsExactly(0, 650, 0)
            assertThat(result.balances.members).isEqualTo(2)
            assertThat(result.balances.total).isEqualTo(450)
            assertThat(result.balances.purchased).isEqualTo(300)
            assertThat(result.balances.rewarded).isEqualTo(50)
            assertThat(result.balances.legacy).isEqualTo(100)
            assertThat(result.balances.expiringIn30Days).isEqualTo(50)
            assertThat(result.balances.buckets.map { it.members }).containsExactly(1, 0, 1, 0, 0)
            val history = service.metrics(AdminChartsRequest().apply { from = "2020-01-01"; to = "2020-01-01" })
            assertThat(history.sinkRate).isNull()
            assertThat(history.balances).isEqualTo(result.balances)
            assertThat(jdbc.queryForObject("SELECT remaining FROM credit_lots WHERE original_amount=40", Long::class.java)).isEqualTo(40)
        } finally { pg.stop() }
    }
}
