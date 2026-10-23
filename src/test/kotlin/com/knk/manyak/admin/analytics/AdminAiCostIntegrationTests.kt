package com.knk.manyak.admin.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.*

@AutoConfigureRestTestClient
@Import(AdminMetricsTestClock::class)
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAiCostIntegrationTests {
    companion object {
        private val server = MockWebServer().apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("manyak.admin.langfuse.host") { server.url("/").toString() }
            r.add("manyak.admin.langfuse.public-key") { "cost-public-key" }
            r.add("manyak.admin.langfuse.secret-key") { "cost-secret-key" }
            r.add("manyak.admin.langfuse.environment") { "prod" }
        }
        @JvmStatic @AfterAll fun stop() { server.shutdown() }
    }
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var clock: Clock
    private lateinit var auth: String
    @BeforeEach fun prepare() {
        cleaner.cleanAll()
        (clock as MutableAnalyticsClock).at = clock.instant().plusSeconds(120)
        val admin = users.save(User(nickname = "비용 관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        auth = "Bearer ${jwt.issueAdminToken(admin.publicId)}"
    }
    private fun respond(body: String, status: Int = 200) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(status).setBody(body) }
    }
    private fun row(trace: String, model: String?, cost: String, tokens: Long = 1000, time: String = "2026-10-21T14:00:00Z") =
        mapOf("traceName" to trace, "providedModelName" to model, "sum_totalCost" to cost, "sum_totalTokens" to tokens, "time_dimension" to time)
    private fun get(period: String = "from=2026-10-21&to=2026-10-23") = client.get().uri("/api/v1/admin/quality/ai-cost?$period").header("Authorization", auth).exchange()
    private fun body(period: String = "from=2026-10-21&to=2026-10-23"): JsonNode = mapper.readTree(get(period).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    private fun call(feature: String, model: String?, input: Int? = 100, output: Int? = 20, status: String = "SUCCEEDED", time: String = "2026-10-21T14:30:00Z") {
        jdbc.update("INSERT INTO ai_call_logs(request_id,caller_service,feature,model,status,input_token_count,output_token_count,created_at,retry_count) VALUES ('cost-test','test',?,?,?,?,?,?,0)",
            feature, model, status, input, output, java.sql.Timestamp.from(Instant.parse(time)))
    }

    @Test fun `DB와 Langfuse 조합 합집합 비용 매핑 원가 분모와 이미지 비용을 계약대로 응답한다`() {
        call("chat_response", "gpt-x"); call("chat_response", "gpt-x"); call("choice_generation", "gpt-x")
        call("chat_response", "gpt-y", null, 20); call("chat_response", null, null, null)
        call("story_completion", "compile-base")
        repeat(3) { call("chat_image", null, null, null) }; call("chat_image", null, status = "FAILED")
        call("chat_response", "gpt-x", status = "FAILED")
        respond(mapper.writeValueAsString(mapOf("data" to listOf(
            row("채팅 턴", "gpt-x", "0.004"), row("채팅 선택지", "gpt-x", "0.001"),
            row("새 기능", "gpt-new", "0.002"), row("스토리 컴파일", "image-model", "0.006"),
            row("채팅 턴", "gpt-w", "0"), row("채팅 턴", null, "0.003"),
            row("채팅 턴", "gpt-x", "0.001", time = "2026-10-21T15:00:00Z"),
            row("채팅 턴", "gpt-x", "0", time = "2026-10-21T13:00:00Z"),
        ))))
        val result = body()
        assertThat(result["pendingDates"].size()).isZero()
        assertThat(result["usdKrwRate"].asInt()).isEqualTo(1400)
        assertThat(result["currency"].asText()).isEqualTo("KRW")
        assertThat(result["daily"].toList().map { it["date"].asText() }).containsExactly("2026-10-21", "2026-10-22", "2026-10-23")
        val entries = result["daily"][0]["byFeature"]
        val turns = entries.single { it["feature"].asText() == "chat_response" && it["model"].asText() == "gpt-x" }
        assertThat(turns["calls"].asInt()).isEqualTo(2)
        assertThat(turns["inputTokens"].asInt()).isEqualTo(200)
        assertThat(turns["outputTokens"].asInt()).isEqualTo(40)
        assertThat(turns["costKrw"].asDouble()).isEqualTo(5.6)
        val missing = entries.single { it["model"].asText() == "gpt-y" }
        assertThat(missing["costKrw"].isNull).isTrue()
        assertThat(missing["callsWithoutTokens"].asInt()).isEqualTo(1)
        val imageModel = entries.single { it["model"].asText() == "image-model" }
        assertThat(imageModel["feature"].asText()).isEqualTo("story_completion")
        assertThat(imageModel["calls"].asInt()).isZero()
        assertThat(imageModel["costKrw"].asDouble()).isEqualTo(8.4)
        assertThat(entries.single { it["feature"].asText() == "chat_image" }["costKrw"].asInt()).isEqualTo(144)
        assertThat(result["daily"][1]["byFeature"][0]["costKrw"].asDouble()).isEqualTo(1.4)
        assertThat(result["unmapped"][0]["traceName"].asText()).isEqualTo("새 기능")
        assertThat(result["unmapped"][0]["costKrw"].asDouble()).isEqualTo(2.8)
        assertThat(result["unpricedModels"].toList().map { it.asText() }).containsExactly("gpt-w")
        assertThat(result["totals"]["costKrw"].asDouble()).isEqualTo(167.8)
        assertThat(result["totals"]["costPerTurnKrw"].asDouble()).isEqualTo(3.15)
        assertThat(result["totals"]["costPerStoryKrw"].asDouble()).isEqualTo(8.4)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_analytics_daily_values WHERE metric='langfuse_ai_cost'", Long::class.java)).isEqualTo(3)
    }

    @Test fun `긴 범위의 pending 날짜에도 DB 호출과 장당 원가를 유지하고 asOf는 가장 이른 수신 시각이다`() {
        respond("{\"data\":[]}")
        call("chat_response", "db-only", time = "2026-07-26T00:00:00Z")
        call("chat_image", null, time = "2026-07-26T00:00:00Z")
        val count = server.requestCount
        val first = body("from=2026-07-26&to=2026-10-23")
        assertThat(server.requestCount - count).isEqualTo(6)
        assertThat(first["daily"].size()).isEqualTo(90)
        assertThat(first["pendingDates"].size()).isEqualTo(72)
        assertThat(first["pendingDates"][0].asText()).isEqualTo("2026-07-26")
        val entries = first["daily"][0]["byFeature"]
        assertThat(entries.single { it["feature"].asText() == "chat_response" }["costKrw"].isNull).isTrue()
        assertThat(entries.single { it["feature"].asText() == "chat_image" }["costKrw"].asInt()).isEqualTo(48)
        (clock as MutableAnalyticsClock).at = clock.instant().plusSeconds(30)
        val second = body("from=2026-07-26&to=2026-10-23")
        assertThat(second["pendingDates"].size()).isEqualTo(54)
        assertThat(second["asOf"].asText()).isEqualTo(first["asOf"].asText())
    }

    @Test fun `9일은 세 번 조회하고 빈 날은 빈 배열과 비용 0으로 저장하며 확정일은 재조회하지 않는다`() {
        respond(mapper.writeValueAsString(mapOf("data" to listOf(row("채팅 턴", "gpt-x", "0.004")))))
        val count = server.requestCount
        val first = body("from=2026-10-15&to=2026-10-23")
        body("from=2026-10-15&to=2026-10-23")
        assertThat(server.requestCount - count).isEqualTo(3)
        assertThat(first["pendingDates"].size()).isZero()
        assertThat(first["totals"]["costKrw"].asDouble()).isEqualTo(5.6)
        val saved = jdbc.queryForList("SELECT metric_date, params, \"value\", finalized_at FROM admin_analytics_daily_values WHERE metric='langfuse_ai_cost'")
        assertThat(saved).hasSize(9)
        assertThat(saved.count { it["finalized_at"] != null }).isEqualTo(7)
        val emptyDay = jdbc.queryForObject("SELECT \"value\" FROM admin_analytics_daily_values WHERE metric='langfuse_ai_cost' AND metric_date='2026-10-17'", String::class.java)!!
        assertThat(mapper.readTree(emptyDay).isArray).isTrue()
        assertThat(mapper.readTree(emptyDay).size()).isZero()
        val params = jdbc.queryForList("SELECT params FROM admin_analytics_daily_values WHERE metric='langfuse_ai_cost'", String::class.java)
        assertThat(params).allSatisfy { assertThat(it).doesNotContain("cost-public-key", "cost-secret-key") }
        val empty = body("from=2026-10-17&to=2026-10-17")
        assertThat(empty["totals"]["costKrw"].asDouble()).isZero()
        assertThat(empty["pendingDates"].size()).isZero()
        assertThat(server.requestCount - count).isEqualTo(3)
    }

    @Test fun `실패 관측 비용도 더하되 성공한 호출과 토큰만 원가의 분모로 사용한다`() {
        call("chat_response", "gpt-x", input = 100, output = 20)
        call("chat_response", "gpt-x", input = 900, output = 900, status = "FAILED")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val query = mapper.readTree(request.requestUrl!!.queryParameter("query"))
                val excludesFailure = query["filters"].any { it["column"].asText() in setOf("level", "status", "statusMessage") }
                val observations = listOf(row("채팅 턴", "gpt-x", "0.004")) +
                    if (excludesFailure) emptyList() else listOf(row("채팅 턴", "gpt-x", "0.002"))
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to observations)))
            }
        }
        val result = body("from=2026-10-21&to=2026-10-21")
        val item = result["daily"][0]["byFeature"].single()
        assertThat(item["calls"].asInt()).isEqualTo(1)
        assertThat(item["inputTokens"].asInt()).isEqualTo(100)
        assertThat(item["outputTokens"].asInt()).isEqualTo(20)
        assertThat(item["costKrw"].asDouble()).isEqualTo(8.4)
        assertThat(result["totals"]["costPerTurnKrw"].asDouble()).isEqualTo(8.4)
    }

    @Test fun `외부 실패는 저장 날짜만 반환하고 없으면 503이며 본문과 키를 로그에 넣지 않는다`(output: CapturedOutput) {
        respond(mapper.writeValueAsString(mapOf("data" to listOf(row("채팅 턴", "gpt-x", "0.004")))))
        val initial = body("from=2026-10-21&to=2026-10-21")
        respond("upstream-private-body", 500)
        val partial = body()
        assertThat(partial["pendingDates"].toList().map { it.asText() }).containsExactly("2026-10-22", "2026-10-23")
        assertThat(partial["asOf"].asText()).isEqualTo(initial["asOf"].asText())
        val count = server.requestCount
        get("from=2026-10-22&to=2026-10-23").expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
        assertThat(server.requestCount).isEqualTo(count)
        assertThat(output.out).contains("admin_ai_cost_fetch_failed reason=http_error status=500")
            .doesNotContain("upstream-private-body", "cost-secret-key", "cost-public-key")
    }

    @Test fun `숫자가 아닌 비용은 parse_error로 기록하고 60초 뒤 복구한다`(output: CapturedOutput) {
        respond(mapper.writeValueAsString(mapOf("data" to listOf(row("채팅 턴", "gpt-x", "oops")))))
        get().expectStatus().isEqualTo(503)
        assertThat(output.out).contains("admin_ai_cost_fetch_failed reason=parse_error status=200")
        val count = server.requestCount
        respond("{\"data\":[]}")
        get().expectStatus().isEqualTo(503)
        assertThat(server.requestCount).isEqualTo(count)
        (clock as MutableAnalyticsClock).at = clock.instant().plusSeconds(60)
        assertThat(body()["pendingDates"].size()).isZero()
    }

    @Test fun `만료된 오늘 저장 값도 갱신 실패 때 반환한다`() {
        respond("{\"data\":[]}")
        val initial = body("from=2026-10-23&to=2026-10-23")
        (clock as MutableAnalyticsClock).at = clock.instant().plusSeconds(600)
        respond("{}", 500)
        val stale = body("from=2026-10-23&to=2026-10-23")
        assertThat(stale["asOf"].asText()).isEqualTo(initial["asOf"].asText())
        assertThat(stale["pendingDates"].size()).isZero()
    }
}
