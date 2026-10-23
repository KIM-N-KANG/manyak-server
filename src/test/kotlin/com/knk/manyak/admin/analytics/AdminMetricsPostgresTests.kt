package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminChartsRequest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.*
import java.math.BigDecimal

class AdminMetricsPostgresTests {
    @Test fun `실제 PostgreSQL도 KST 경계 최초 연동 팀 제외와 누락 토큰 원가를 집계한다`() {
        val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD","metrics-test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2))
        try {
            pg.start()
            val ds=DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres","postgres","metrics-test")
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            val jdbc=JdbcTemplate(ds)
            val member=jdbc.queryForObject("INSERT INTO users(nickname,created_at,migrated_at) VALUES ('member','2026-10-20T15:00:00Z','2026-10-20T16:00:00Z') RETURNING id",Long::class.java)!!
            val team=jdbc.queryForObject("INSERT INTO users(nickname,created_at) VALUES ('team','2026-10-20T15:00:00Z') RETURNING id",Long::class.java)!!
            jdbc.update("INSERT INTO users(nickname,created_at) VALUES ('before','2026-10-20T14:59:59.999Z'),('end','2026-10-21T15:00:00Z')")
            jdbc.update("INSERT INTO social_accounts(user_id,provider,provider_user_id,connected_at) VALUES (?,'GOOGLE','g','2026-10-20T15:00:00Z'),(?,'KAKAO','k','2026-10-20T15:00:01Z')",member,member)
            val story=jdbc.queryForObject("INSERT INTO stories(user_id,title,genre) VALUES (?,'story','판타지') RETURNING id",Long::class.java,member)!!
            jdbc.update("INSERT INTO story_chats(user_id,story_id,current_turn,persona_name_snapshot,persona_description_snapshot,model_code,created_at) VALUES (?,?,7,'p','description','LITE','2026-10-20T15:00:00Z'),(NULL,?,5,NULL,NULL,'LITE','2026-10-20T15:00:00Z'),(?,?,100,NULL,NULL,'LITE','2026-10-20T15:00:00Z')",member,story,story,team,story)
            val teamStory=jdbc.queryForObject("INSERT INTO stories(user_id,title,genre) VALUES (?,'team story','판타지') RETURNING id",Long::class.java,team)!!
            jdbc.update("INSERT INTO ai_call_logs(request_id,caller_service,feature,model,status,input_token_count,output_token_count,story_id,created_at) VALUES ('a','test','chat_response','priced','SUCCEEDED',2000,1000,?,'2026-10-20T15:00:00Z'),('b','test','chat_response',NULL,'SUCCEEDED',NULL,NULL,?,'2026-10-20T15:00:00Z'),('c','test','story_completion','unknown','SUCCEEDED',1,1,?,'2026-10-20T15:00:00Z')",teamStory,teamStory,teamStory)
            val service=AdminMetricReadService(AdminMetricReadRepository(NamedParameterJdbcTemplate(ds)),AdminDashboardProperties(listOf(team)),AdminAiPrices(mapOf("priced" to TokenPrice(BigDecimal("1000"),BigDecimal("4000")))),Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"),ZoneOffset.UTC))
            val q=AdminChartsRequest().apply { from="2026-10-21"; to="2026-10-21" }
            assertThat(service.signups(q).daily.single().signups).isEqualTo(SignupCounts(1,1,0,0))
            assertThat(service.reach(q).segments[0].chats).isEqualTo(2)
            assertThat(service.reach(q).guestToMember).isEqualTo(GuestConversion(1,1,1.0))
            assertThat(service.options(q).daily.single().withPersona).isEqualTo(1)
            val cost=service.aiCost(q)
            assertThat(cost.totals.costKrw).isEqualByComparingTo("6"); assertThat(cost.totals.costPerTurnKrw).isEqualByComparingTo("3")
            assertThat(cost.daily.single().byFeature.single { it.model==null }.callsWithoutTokens).isEqualTo(1)
            assertThat(cost.unpricedModels).containsExactly("unknown")
        } finally { pg.stop() }
    }
}
