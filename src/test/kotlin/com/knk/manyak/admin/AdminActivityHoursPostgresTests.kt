package com.knk.manyak.admin

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.repository.AdminActivityHoursRepository
import com.knk.manyak.admin.service.AdminActivityHoursService
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.*
import java.util.UUID

class AdminActivityHoursPostgresTests {
    @Test fun `실제 PostgreSQL UTC와 Asia Seoul 세션에서 네 지표의 KST 요일과 시가 같다`() {
        val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "activity-test")
            .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
        pg.start()
        try {
            val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "activity-test")
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            // SET TIME ZONE와 집계를 반드시 같은 물리 연결에서 실행한다.
            ds.connection.use { connection ->
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                val now = Instant.parse("2026-11-03T11:10:00Z")
                val sunday = Instant.parse("2026-11-01T14:59:59.999999Z").atOffset(ZoneOffset.UTC)
                val monday = Instant.parse("2026-11-01T15:00:00Z").atOffset(ZoneOffset.UTC)
                val old = Instant.parse("2020-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
                fun user(at: OffsetDateTime): Long = jdbc.queryForObject(
                    "INSERT INTO users(public_id,nickname,created_at) VALUES (?,?,?) RETURNING id",
                    Long::class.java, UUID.randomUUID(), UUID.randomUUID().toString(), at)!!
                fun social(id: Long, provider: String = "GOOGLE") {
                    jdbc.update("INSERT INTO social_accounts(user_id,provider,provider_user_id) VALUES (?,?,?)", id, provider, UUID.randomUUID().toString())
                }
                val member = user(sunday); social(member); social(member, "KAKAO")
                val team = user(monday); social(team)
                user(monday) // 소셜 없는 회원 제외
                val sid = jdbc.queryForObject("INSERT INTO stories(public_id,user_id,title,created_at,deleted_at) VALUES (?,?, 'PG',?,?) RETURNING id",
                    Long::class.java, UUID.randomUUID(), member, monday, monday)!!
                jdbc.update("INSERT INTO stories(public_id,user_id,title,created_at) VALUES (?,?, 'team',?)", UUID.randomUUID(), team, monday)
                fun chat(owner: Long?) = jdbc.queryForObject(
                    "INSERT INTO story_chats(public_id,story_id,user_id,created_at,deleted_at) VALUES (?,?,?,?,?) RETURNING id",
                    Long::class.java, UUID.randomUUID(), sid, owner, old, monday)!!
                fun message(cid: Long, at: OffsetDateTime, role: String = "USER") {
                    jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order,created_at,hidden_at) VALUES (?,?,'PG',?,?,?)",
                        cid, role, jdbc.queryForObject("SELECT COUNT(*)+1 FROM story_messages WHERE chat_id=?", Int::class.java, cid), at, monday)
                }
                val c = chat(member)
                message(c, sunday); message(c, monday); message(chat(null), monday); message(chat(team), monday)
                message(c, monday, "ASSISTANT")
                message(c, now.plusNanos(1000).atOffset(ZoneOffset.UTC)) // asOf 뒤
                fun order(owner: Long, at: OffsetDateTime?, status: String) {
                    jdbc.update("INSERT INTO credit_orders(public_id,user_id,product_id,provider,status,price_krw,credit_amount,created_at,completed_at) VALUES (?,?,'if_2000','GROBLE',?,2000,2000,?,?)",
                        UUID.randomUUID(), owner, status, old, at)
                }
                order(member, sunday, "COMPLETED"); order(member, monday, "REFUNDED")
                order(member, null, "PENDING"); order(team, monday, "COMPLETED")
                val repository = AdminActivityHoursRepository(NamedParameterJdbcTemplate(jdbc))
                val service = AdminActivityHoursService(repository, AdminDashboardProperties(listOf(team)), Clock.fixed(now, ZoneOffset.UTC))
                val request = AdminChartsRequest().apply { from = "2026-11-01"; to = "2026-11-03" }
                jdbc.execute("SET TIME ZONE 'UTC'")
                val utc = service.activityHours(request)
                jdbc.execute("SET TIME ZONE 'Asia/Seoul'")
                val seoul = service.activityHours(request)
                assertThat(seoul).isEqualTo(utc)
                val metrics = utc.metrics
                assertThat(metrics.signups.total).isEqualTo(1)
                assertThat(metrics.signups.cells.single { it.weekday == 7 && it.hour == 23 }.count).isEqualTo(1)
                assertThat(metrics.messages.total).isEqualTo(3)
                assertThat(metrics.messages.cells.single { it.weekday == 7 && it.hour == 23 }.count).isEqualTo(1)
                assertThat(metrics.messages.cells.single { it.weekday == 1 && it.hour == 0 }.count).isEqualTo(2)
                assertThat(metrics.stories.total).isEqualTo(1)
                assertThat(metrics.stories.cells.single { it.weekday == 1 && it.hour == 0 }.count).isEqualTo(1)
                assertThat(metrics.payments.total).isEqualTo(2)
                assertThat(metrics.payments.cells.single { it.weekday == 7 && it.hour == 23 }.count).isEqualTo(1)
                assertThat(metrics.payments.cells.single { it.weekday == 1 && it.hour == 0 }.count).isEqualTo(1)
                val all = AdminActivityHoursService(repository, AdminDashboardProperties(emptyList()), Clock.fixed(now, ZoneOffset.UTC)).activityHours(request)
                assertThat(all.metrics.signups.total).isEqualTo(2)
                assertThat(all.metrics.messages.total).isEqualTo(4)
                assertThat(all.metrics.stories.total).isEqualTo(2)
                assertThat(all.metrics.payments.total).isEqualTo(3)
            }
        } finally { pg.stop() }
    }
}
