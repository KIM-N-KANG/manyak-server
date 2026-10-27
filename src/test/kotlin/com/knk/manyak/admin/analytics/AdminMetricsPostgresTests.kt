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
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class AdminMetricsPostgresTests {
    @Test fun `실제 PostgreSQL도 KST 경계 최초 연동 팀 제외와 누락 토큰 원가를 집계한다`() {
        val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD","metrics-test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2))
        val langfuse = MockWebServer().apply { start() }
        try {
            pg.start()
            val ds=DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres","postgres","metrics-test")
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            val jdbc=JdbcTemplate(ds)
            val member=jdbc.queryForObject("INSERT INTO users(nickname,created_at,migrated_at) VALUES ('member','2026-10-20T15:00:00Z','2026-10-20T16:00:00Z') RETURNING id",Long::class.java)!!
            val team=jdbc.queryForObject("INSERT INTO users(nickname,created_at) VALUES ('team','2026-10-20T15:00:00Z') RETURNING id",Long::class.java)!!
            val before=jdbc.queryForObject("INSERT INTO users(nickname,created_at,status) VALUES ('before','2026-10-20T14:59:59.999Z','DELETED') RETURNING id",Long::class.java)!!
            jdbc.update("INSERT INTO users(nickname,created_at,status) VALUES ('end','2026-10-21T15:00:00Z','ACTIVE'),('rejoined-old','2026-10-20T14:00:00Z','DELETED'),('rejoined-old-today','2026-10-20T16:00:00Z','DELETED')")
            jdbc.update("INSERT INTO social_accounts(user_id,provider,provider_user_id,connected_at,deleted_at) VALUES (?,'KAKAO','before','2026-10-20T14:59:59.999Z','2026-10-20T15:00:00Z')",before)
            val teamBefore=jdbc.queryForObject("INSERT INTO users(nickname,created_at) VALUES ('team-before','2026-10-20T14:59:59.999Z') RETURNING id",Long::class.java)!!
            jdbc.update("INSERT INTO social_accounts(user_id,provider,provider_user_id,connected_at) VALUES (?,'GOOGLE','g','2026-10-20T15:00:00Z'),(?,'KAKAO','k','2026-10-20T15:00:01Z')",member,member)
            val story=jdbc.queryForObject("INSERT INTO stories(user_id,title,genre) VALUES (?,'story','판타지') RETURNING id",Long::class.java,member)!!
            jdbc.update("INSERT INTO story_chats(user_id,story_id,current_turn,persona_name_snapshot,persona_description_snapshot,model_code,created_at) VALUES (?,?,7,'p','description','LITE','2026-10-20T15:00:00Z'),(NULL,?,5,NULL,NULL,'LITE','2026-10-20T15:00:00Z'),(?,?,100,NULL,NULL,'LITE','2026-10-20T15:00:00Z')",member,story,story,team,story)
            val teamStory=jdbc.queryForObject("INSERT INTO stories(user_id,title,genre) VALUES (?,'team story','판타지') RETURNING id",Long::class.java,team)!!
            jdbc.update("INSERT INTO ai_call_logs(request_id,caller_service,feature,model,status,input_token_count,output_token_count,story_id,created_at) VALUES ('a','test','chat_response','priced','SUCCEEDED',2000,1000,?,'2026-10-20T15:00:00Z'),('b','test','chat_response',NULL,'SUCCEEDED',NULL,NULL,?,'2026-10-20T15:00:00Z'),('c','test','story_completion','unknown','SUCCEEDED',1,1,?,'2026-10-20T15:00:00Z')",teamStory,teamStory,teamStory)
            val clock = Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC)
            val repository = AdminMetricReadRepository(NamedParameterJdbcTemplate(ds))
            val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
            val store = AnalyticsValueRepository(NamedParameterJdbcTemplate(ds), mapper, DataSourceTransactionManager(ds))
            val budget = AmplitudeBudget(clock)
            val service = AdminMetricReadService(repository, AdminDashboardProperties(listOf(team, teamBefore)), MetricDefinitions(AnalyticsQueryFactory(mapper), mapper),
                AnalyticsSnapshots(store, AmplitudeRestClient(AmplitudeDashboardProperties(), mapper, budget), budget, mapper, clock), clock)
            val props = AdminLangfuseProperties(langfuse.url("/").toString(), "public", "secret", "prod")
            val snapshots = LangfuseCostSnapshots(store, LangfuseMetricsClient(props, mapper), props, mapper, clock)
            val costs = AdminAiCostService(repository, snapshots, AdminAiCostProperties(traceFeatures = mapOf("채팅 턴" to "chat_response", "스토리 컴파일" to "story_completion")), clock)
            langfuse.enqueue(MockResponse().setBody("""{"data":[
                {"time_dimension":"2026-10-21T00:00:00Z","traceName":"채팅 턴","providedModelName":"priced","sum_totalCost":"0.004","sum_totalTokens":"3000"},
                {"time_dimension":"2026-10-21T00:00:00Z","traceName":"스토리 컴파일","providedModelName":"unknown","sum_totalCost":0,"sum_totalTokens":"2"}
            ]}"""))
            val q=AdminChartsRequest().apply { from="2026-10-21"; to="2026-10-21" }
            // 연동 행이 없는 회원(재가입 예전 계정)은 빼고 탈퇴 tombstone 연동 행은 행으로 센다.
            assertThat(service.signups(q).daily.single().signups).isEqualTo(SignupCounts(1,1,0))
            assertThat(service.signups(q).daily.single().cumulativeSignups).isEqualTo(2)
            assertThat(service.signups(q).pendingDates).containsExactly(LocalDate.parse("2026-10-21"))
            val segmented=AdminSegmentRequest().apply { from="2026-10-21"; to="2026-10-21" }
            assertThat(service.reach(segmented).segments[0].chats).isEqualTo(2)
            assertThat(service.reach(segmented).guestToMember).isEqualTo(GuestConversion(1,1,1.0))
            assertThat(service.options(segmented).daily.single().withPersona).isEqualTo(1)
            assertThat(repository.modelCodes()).containsExactly("AURORA", "LITE")
            val cost=costs.aiCost(q)
            assertThat(cost.totals.costKrw).isEqualByComparingTo("5.6"); assertThat(cost.totals.costPerTurnKrw).isEqualByComparingTo("2.8")
            assertThat(cost.daily.single().byFeature.single { it.model==null }.callsWithoutTokens).isEqualTo(1)
            assertThat(cost.unpricedModels).containsExactly("unknown")
            jdbc.update("INSERT INTO ai_call_logs(request_id,caller_service,feature,status,created_at) SELECT 'image','test','chat_image','SUCCEEDED','2026-10-20T15:00:00Z'::timestamptz FROM generate_series(1,3)")
            jdbc.update("INSERT INTO ai_call_logs(request_id,caller_service,feature,status,created_at) VALUES ('image-failed','test','chat_image','FAILED','2026-10-20T15:00:00Z')")
            val withImages=costs.aiCost(q)
            val image=withImages.daily.single().byFeature.single { it.feature=="chat_image" }
            assertThat(image.calls).isEqualTo(3); assertThat(image.costKrw).isEqualByComparingTo("144")
            assertThat(image.callsWithoutTokens).isZero(); assertThat(image.inputTokens).isZero(); assertThat(image.outputTokens).isZero()
            assertThat(withImages.totals.costKrw).isEqualByComparingTo("149.6")
            assertThat(withImages.totals.costPerTurnKrw).isEqualByComparingTo("2.8")
            assertThat(withImages.unpricedModels).containsExactly("unknown")
            assertThat(langfuse.requestCount).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_analytics_daily_values WHERE metric='langfuse_ai_cost' AND finalized_at IS NOT NULL", Long::class.java)).isEqualTo(1)
            val restarted = AdminAiCostService(repository, LangfuseCostSnapshots(store, LangfuseMetricsClient(props, mapper), props, mapper, clock), AdminAiCostProperties(traceFeatures = mapOf("채팅 턴" to "chat_response", "스토리 컴파일" to "story_completion")), clock)
            assertThat(restarted.aiCost(q).totals).isEqualTo(withImages.totals)
            assertThat(langfuse.requestCount).isEqualTo(1)
        } finally { pg.stop(); langfuse.shutdown() }
    }
}
