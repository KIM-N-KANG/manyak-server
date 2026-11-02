package com.knk.manyak.admin

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.repository.AdminChatEngagementRepository
import com.knk.manyak.admin.service.AdminChatEngagementService
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

class AdminChatEngagementPostgresTests {
    @Test fun `실제 PostgreSQL에서 KST 활동 쌍과 판본 시각 및 30일 창을 집계한다`() {
        val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "engagement-test")
            .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
        pg.start()
        try {
            val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "engagement-test")
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            val jdbc = JdbcTemplate(ds)
            val now = Instant.parse("2026-10-20T15:30:00Z")
            val before = Instant.parse("2026-10-20T14:59:59.999999Z")
            val midnight = Instant.parse("2026-10-20T15:00:00Z")
            val windowStart = Instant.parse("2026-09-21T15:00:00Z")
            val old = Instant.parse("2020-01-01T00:00:00Z")
            fun user(): Long {
                val id = UUID.randomUUID()
                return jdbc.queryForObject("INSERT INTO users(public_id,nickname) VALUES (?,?) RETURNING id", Long::class.java, id, id.toString())!!
            }
            val a = user(); val b = user(); val team = user()
            // 스토리 소유자는 팀이어도 회원 채팅의 지표는 채팅 소유자로 제외한다.
            val story = jdbc.queryForObject("INSERT INTO stories(public_id,user_id,title) VALUES (?,?,'몰입 PG') RETURNING id",
                Long::class.java, UUID.randomUUID(), team)!!
            fun chat(owner: Long?, deleted: Boolean = false) = jdbc.queryForObject(
                "INSERT INTO story_chats(public_id,story_id,user_id,regenerated_count,deleted_at,created_at) VALUES (?,?,?,999,?,?) RETURNING id",
                Long::class.java, UUID.randomUUID(), story, owner, if (deleted) now.atOffset(ZoneOffset.UTC) else null, old.atOffset(ZoneOffset.UTC))!!
            fun message(cid: Long, at: Instant = now, role: String = "USER") = jdbc.queryForObject(
                "INSERT INTO story_messages(chat_id,role,content,message_order,created_at) VALUES (?,?,'fixture',?,?) RETURNING id",
                Long::class.java, cid, role,
                jdbc.queryForObject("SELECT COUNT(*)+1 FROM story_messages WHERE chat_id=?", Int::class.java, cid), at.atOffset(ZoneOffset.UTC))!!
            fun version(mid: Long, at: Instant = now) {
                val number = jdbc.queryForObject("SELECT COUNT(*)+1 FROM story_message_versions WHERE message_id=?", Int::class.java, mid)
                jdbc.update("INSERT INTO story_message_versions(message_id,version_number,content,choices,created_at) VALUES (?,?,'old fixture','[]',?)",
                    mid, number, at.atOffset(ZoneOffset.UTC))
            }
            val ca = chat(a, deleted = true); val cb = chat(b); val guest = chat(null); val excluded = chat(team)
            message(ca, windowStart); message(chat(user()), windowStart)
            message(chat(user()), windowStart.minusNanos(1000)) // 30일 창 밖
            message(ca, before); message(cb, before)
            message(ca, midnight); message(ca); message(chat(b)) // B는 다른 채팅만 이어 쓴다.
            message(guest); message(excluded)
            message(ca, now.plusNanos(1000))
            message(ca, before, "ASSISTANT")
            message(ca, role = "ASSISTANT"); message(cb, role = "ASSISTANT")
            val guestAssistant = message(guest, role = "ASSISTANT")
            val teamAssistant = message(excluded, role = "ASSISTANT")
            message(ca, now.plusNanos(1000), "ASSISTANT")
            val original = message(ca, old, "ASSISTANT")
            version(original, before); version(original, midnight); version(original)
            version(original, now.plusNanos(1000)); version(guestAssistant); version(teamAssistant)
            val repository = AdminChatEngagementRepository(NamedParameterJdbcTemplate(jdbc))
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val service = AdminChatEngagementService(repository, AdminDashboardProperties(listOf(team)), clock)
            val request = AdminChartsRequest().apply { from = "2026-10-21"; to = "2026-10-21" }
            val result = service.engagement(request)
            assertThat(result.activeMembers).isEqualTo(2)
            assertThat(result.monthlyActiveMembers).isEqualTo(3)
            assertThat(result.turnsPerActiveMember).isEqualTo(1.5)
            val d = result.daily.single()
            assertThat(d.turns).isEqualTo(3)
            assertThat(d.continuedMembers).isEqualTo(1)
            assertThat(d.continuationRate).isEqualTo(0.5)
            assertThat(d.regenerations).isEqualTo(3)
            assertThat(d.regenerationRate).isEqualTo(1.0)
            assertThat(d.stickiness).isEqualTo(2.0 / 3)
            val historical = service.engagement(AdminChartsRequest().apply { from = "2026-10-20"; to = "2026-10-20" }).daily.single()
            assertThat(historical.activeMembers).isEqualTo(2)
            assertThat(historical.turns).isEqualTo(1)
            assertThat(historical.regenerations).isEqualTo(1)
            val all = AdminChatEngagementService(repository, AdminDashboardProperties(emptyList()), clock).engagement(request)
            assertThat(all.activeMembers).isEqualTo(3)
            assertThat(all.monthlyActiveMembers).isEqualTo(4)
            assertThat(all.daily.single().turns).isEqualTo(4)
            assertThat(all.daily.single().regenerations).isEqualTo(4)
        } finally { pg.stop() }
    }
}
