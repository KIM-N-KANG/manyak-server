package com.knk.manyak.admin

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminStoryConsumptionRequest
import com.knk.manyak.admin.repository.AdminStoryConsumptionRepository
import com.knk.manyak.admin.service.AdminStoryConsumptionService
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.service.OfficialStoryAccount
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.*
import java.util.UUID

class AdminStoryConsumptionPostgresTests {
    @Test fun `실제 PostgreSQL에서 공식 계정 예외 순위 UUID 동률과 창작자 직전 기간을 집계한다`() {
        val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "consumption-test")
            .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
        pg.start()
        try {
            val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "consumption-test")
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            val jdbc = JdbcTemplate(ds)
            val now = Instant.parse("2026-10-20T15:30:00Z")
            val current = OffsetDateTime.parse("2026-10-20T15:00:00Z")
            val previous = OffsetDateTime.parse("2026-10-20T14:59:59.999999Z")
            val old = OffsetDateTime.parse("2020-01-01T00:00:00Z")
            fun user(publicId: UUID = UUID.randomUUID()) = jdbc.queryForObject(
                "INSERT INTO users(public_id,nickname) VALUES (?,?) RETURNING id", Long::class.java, publicId, publicId.toString())!!
            val a = user(); val b = user(); val officialPublicId = UUID.randomUUID()
            val official = user(officialPublicId); val team = user()
            fun story(owner: Long?, id: UUID = UUID.randomUUID(), at: OffsetDateTime = old,
                      visibility: String = "PUBLIC", deleted: Boolean = false) = jdbc.queryForObject(
                "INSERT INTO stories(public_id,user_id,title,status,visibility,created_at,deleted_at) VALUES (?,?,'현재 제목','PUBLISHED',?,?,?) RETURNING id",
                Long::class.java, id, owner, visibility, at, if (deleted) current else null)!!
            fun chat(sid: Long, owner: Long?, turns: Int = 0, deleted: Boolean = false, at: OffsetDateTime = current) = jdbc.queryForObject(
                "INSERT INTO story_chats(public_id,story_id,user_id,current_turn,deleted_at,created_at) VALUES (?,?,?,?,?,?) RETURNING id",
                Long::class.java, UUID.randomUUID(), sid, owner, turns, if (deleted) current else null, at)!!
            fun message(cid: Long, role: String = "USER", at: OffsetDateTime = current) {
                jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order,created_at) VALUES (?,?,'fixture',1,?)", cid, role, at)
            }
            val s = story(a)
            chat(s, b, 10); chat(s, b, 20)
            chat(s, a, 999); chat(s, null, 999); chat(s, b, 999, deleted = true)
            chat(s, team, 999); chat(s, b, 999, at = previous)
            chat(s, b, 999, at = current.plusHours(1))
            val officialStory = story(official, at = current)
            repeat(5) { chat(officialStory, b, 2) }
            message(chat(officialStory, official))
            message(chat(officialStory, team))
            chat(story(team, at = current), b, 999)
            val low = UUID.fromString("00000000-0000-4000-8000-000000000002")
            val high = UUID.fromString("ffffffff-0000-4000-8000-000000000002")
            chat(story(a, low, current, "PRIVATE", deleted = true), b, 9)
            chat(story(a, high, current, "PRIVATE"), b, 9)
            story(a, at = previous, visibility = "PRIVATE")
            message(chat(s, b, deleted = true, at = old))
            message(chat(s, a), "ASSISTANT")
            message(chat(s, null))
            val userRepository = Mockito.mock(UserRepository::class.java)
            Mockito.`when`(userRepository.findByPublicId(officialPublicId)).thenReturn(User(id = official, publicId = officialPublicId, nickname = "공식 PG"))
            val repository = AdminStoryConsumptionRepository(NamedParameterJdbcTemplate(jdbc))
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val service = AdminStoryConsumptionService(repository, AdminDashboardProperties(listOf(official, team)),
                OfficialStoryAccount(userRepository, officialPublicId.toString()), clock)
            val request = AdminStoryConsumptionRequest().apply { from = "2026-10-21"; to = "2026-10-21" }
            val result = service.consumption(request)
            assertThat(result.publicStories).isEqualTo(2)
            assertThat(result.officialStories).isEqualTo(1)
            assertThat(result.playedStories).isEqualTo(2)
            assertThat(result.playedRate).isEqualTo(1.0)
            assertThat(result.otherMemberChats).isEqualTo(9)
            assertThat(result.topConcentration).isEqualTo(1.0)
            assertThat(result.ranking.map { it.chats }).containsExactly(5, 2, 1, 1)
            assertThat(result.ranking[0].official).isTrue()
            assertThat(result.ranking[1].players).isEqualTo(1)
            assertThat(result.ranking[1].turns).isEqualTo(30)
            assertThat(result.ranking.drop(2).map { it.storyId }).containsExactly(low, high)
            assertThat(result.ranking[2].deleted).isTrue()
            assertThat(result.creators.activeMembers).isEqualTo(1)
            assertThat(result.creators.members).isEqualTo(1)
            assertThat(result.creators.rate).isEqualTo(1.0)
            assertThat(result.creators.previousMembers).isEqualTo(1)
            assertThat(result.creators.retained).isEqualTo(1)
            assertThat(result.creators.retentionRate).isEqualTo(1.0)
            // 설정 미지정·빈 제외 목록도 실제 PG의 nullable 파라미터 바인딩으로 검증한다.
            val unconfigured = AdminStoryConsumptionService(repository, AdminDashboardProperties(emptyList()),
                OfficialStoryAccount(userRepository, ""), clock).consumption(request)
            assertThat(unconfigured.officialStories).isZero()
            assertThat(unconfigured.publicStories).isEqualTo(3)
            assertThat(unconfigured.ranking.none { it.official }).isTrue()
            assertThat(unconfigured.creators.members).isEqualTo(3)
        } finally { pg.stop() }
    }
}
