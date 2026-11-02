package com.knk.manyak.admin

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.repository.AdminPaymentMetricsRepository
import com.knk.manyak.admin.service.AdminPaymentMetricsService
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.*
import java.util.UUID

class AdminPaymentMetricsPostgresTests {
    @Test fun `결제 지표는 실제 PostgreSQL에서 완료 동률 환불 KST 날짜와 팀 제외를 적용한다`() {
        val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "payment-metrics-test")
            .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
        pg.start()
        try {
            val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "payment-metrics-test")
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            val jdbc = JdbcTemplate(ds)
            fun user(name: String, joined: String) = jdbc.queryForObject("INSERT INTO users(public_id,nickname,created_at) VALUES (?,?,?) RETURNING id",
                Long::class.java, UUID.randomUUID(), name, OffsetDateTime.parse(joined))!!
            val a = user("첫 결제", "2026-10-19T14:30:00Z") // KST 10-19 23:30
            val b = user("재결제", "2026-01-01T00:00:00Z")
            val team = user("제외 팀", "2026-01-01T00:00:00Z")
            fun order(owner: Long, at: String, product: String, price: Long, credits: Long, status: String = "COMPLETED") {
                jdbc.update("""INSERT INTO credit_orders(public_id,user_id,product_id,provider,status,price_krw,credit_amount,completed_at)
                    VALUES (?,?,?,'GROBLE',?,?,?,?)""", UUID.randomUUID(), owner, product, status, price, credits, OffsetDateTime.parse(at))
            }
            val paid = "2026-10-19T15:15:00Z" // 45분 뒤지만 KST 다음날이라 1일 구간
            order(a, paid, "if_2000", 2000, 2000, "REFUNDED")
            order(a, paid, "if_5000", 4800, 5000)
            order(b, "2026-10-18T00:00:00Z", "if_2000", 2000, 2000, "REFUNDED")
            order(b, paid, "if_2000", 2000, 2000)
            order(team, paid, "TEAM_ONLY", 5000, 5000, "REFUNDED")
            val story = jdbc.queryForObject("INSERT INTO stories(public_id,title) VALUES (?,'지표') RETURNING id", Long::class.java, UUID.randomUUID())!!
            for ((owner, role) in listOf(a to "USER", b to "ASSISTANT", team to "USER", null to "USER")) {
                val chat = jdbc.queryForObject("INSERT INTO story_chats(public_id,story_id,user_id,deleted_at) VALUES (?,?,?,now()) RETURNING id",
                    Long::class.java, UUID.randomUUID(), story, owner)!!
                jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order,created_at) VALUES (?,?,'원문',1,?)", chat, role, OffsetDateTime.parse(paid))
            }
            val service = AdminPaymentMetricsService(AdminPaymentMetricsRepository(NamedParameterJdbcTemplate(jdbc)),
                AdminDashboardProperties(listOf(team)), Clock.fixed(Instant.parse("2026-10-20T15:30:00Z"), ZoneOffset.UTC))
            val result = service.metrics(AdminChartsRequest().apply { from = "2026-10-20"; to = "2026-10-20" })
            assertThat(result.activeMembers).isEqualTo(1)
            assertThat(result.payingMembers).isEqualTo(2)
            assertThat(result.conversionRate).isEqualTo(2.0)
            assertThat(result.orders).isEqualTo(3)
            assertThat(result.amountKrw).isEqualTo(8800)
            assertThat(result.arppu).isEqualTo(4400.0)
            assertThat(result.firstTime.orders).isEqualTo(1)
            assertThat(result.firstTime.amountKrw).isEqualTo(2000)
            assertThat(result.repeat.orders).isEqualTo(2)
            assertThat(result.repeat.amountKrw).isEqualTo(6800)
            assertThat(result.refundedOrders).isEqualTo(1)
            assertThat(result.refundRate).isEqualTo(1.0 / 3)
            assertThat(result.products.map { it.productId }).containsExactly("if_5000", "if_2000")
            assertThat(result.products[0].creditAmount).isEqualTo(5000)
            assertThat(result.daysToFirstPayment.map { it.members }).containsExactly(0, 1, 0, 0, 0)
        } finally { pg.stop() }
    }
}
