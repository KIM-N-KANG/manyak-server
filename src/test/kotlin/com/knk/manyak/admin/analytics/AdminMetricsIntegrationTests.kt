package com.knk.manyak.admin.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.*

@AutoConfigureRestTestClient
@org.springframework.context.annotation.Import(AdminMetricsTestClock::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.admin.dashboard.excluded-user-ids="])
class AdminMetricsIntegrationTests {
    companion object {
        private val server = MockWebServer().apply { start() }
        private val langfuse = MockWebServer().apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("manyak.admin.langfuse.host") { langfuse.url("/").toString() }
            r.add("manyak.admin.langfuse.public-key") { "mock" }
            r.add("manyak.admin.langfuse.secret-key") { "mock" }
            r.add("manyak.admin.langfuse.environment") { "prod" }
            r.add("manyak.analytics.amplitude.api-key") { "mock" }; r.add("manyak.analytics.amplitude.secret-key") { "mock" }
            r.add("manyak.analytics.amplitude.dashboard-base-url") { server.url("/").toString() }
        }
        @JvmStatic @AfterAll fun stop() { server.shutdown(); langfuse.shutdown() }
    }
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var reads: AdminMetricReadService
    @Autowired private lateinit var costs: AdminAiCostService
    @Autowired private lateinit var stories: com.knk.manyak.story.repository.StoryRepository
    @Autowired private lateinit var chats: com.knk.manyak.chat.repository.StoryChatRepository
    @Autowired private lateinit var socials: com.knk.manyak.auth.repository.SocialAccountRepository
    @Autowired private lateinit var clock: Clock
    private val mapper = ObjectMapper()
    @BeforeEach fun prepareLangfuse() {
        (clock as MutableAnalyticsClock).at = clock.instant().plusSeconds(120)
        // DB 지표도 Amplitude 플랫폼 값을 부른다. 기본 대기열 대역은 응답을 기다리며 막히므로 실패로 바로 돌려준다.
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500) }
        langfuse.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"data\":[]}")
        }
    }
    private fun langfuseCosts(vararg rows: Triple<String, String, String>) {
        langfuse.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to rows.map { (trace, model, cost) ->
                mapOf("time_dimension" to "2026-10-21T00:00:00Z", "traceName" to trace, "providedModelName" to model, "sum_totalCost" to cost, "sum_totalTokens" to "1000")
            })))
        }
    }
    @Test fun `이미지 성공 3장은 144원이고 Langfuse 본문 비용과 합산하되 턴당 원가에는 섞지 않는다`() {
        langfuseCosts(Triple("채팅 턴", "priced", "0.004"))
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "이미지 원가 관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        for ((i, model) in listOf(null, "image-unknown", "priced").withIndex()) {
            jdbc.update("INSERT INTO ai_call_logs(retry_count,request_id,caller_service,feature,model,status,input_token_count,output_token_count,created_at) VALUES (0,?,'test','chat_image',?,'SUCCEEDED',9,9,'2026-10-21T00:00:00Z')", "image-$i", model)
        }
        jdbc.update("INSERT INTO ai_call_logs(retry_count,request_id,caller_service,feature,status,created_at) VALUES (0,'image-failed','test','chat_image','FAILED','2026-10-21T00:00:00Z')")
        jdbc.update("INSERT INTO ai_call_logs(retry_count,request_id,caller_service,feature,model,status,input_token_count,output_token_count,created_at) VALUES (0,'body','test','chat_response','priced','SUCCEEDED',1000,500,'2026-10-21T00:00:00Z')")
        val body = mapper.readTree(client.get().uri("/api/v1/admin/quality/ai-cost?from=2026-10-21&to=2026-10-21")
            .header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        val images = body["daily"][0]["byFeature"].filter { it["feature"].asText() == "chat_image" }
        assertThat(images.sumOf { it["calls"].asLong() }).isEqualTo(3)
        assertThat(images.sumOf { it["costKrw"].asDouble() }).isEqualTo(144.0)
        for (image in images) for (key in listOf("callsWithoutTokens", "inputTokens", "outputTokens")) assertThat(image[key].asLong()).isZero()
        assertThat(body["chatImageUnitCostKrw"].asDouble()).isEqualTo(48.0)
        assertThat(body["totals"]["costKrw"].asDouble()).isEqualTo(149.6)
        assertThat(body["totals"]["costPerTurnKrw"].asDouble()).isEqualTo(5.6)
        assertThat(body["totals"]["costPerStoryKrw"].isNull).isTrue()
        assertThat(body["unpricedModels"].size()).isZero()
    }

    @Test fun `아홉 관리자 지표 API의 권한과 DB 장애 격리 계약`() {
        cleaner.cleanAll()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500) }
        val admin = users.save(User(nickname = "관리자")); val ordinary = users.save(User(nickname = "회원"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val token = jwt.issueAdminToken(admin.publicId)
        val db = listOf("/users/signups", "/conversion/turn-reach", "/quality/chat-options")
        langfuse.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500) }
        val external = listOf("/quality/ai-cost", "/users/activity", "/users/retention", "/users/sessions", "/users/session-lengths", "/conversion/funnels/web-first-visit", "/quality/client")
        for (path in db + external) {
            client.get().uri("/api/v1/admin$path").exchange().expectStatus().isUnauthorized
            client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer ${jwt.issueAdminToken(ordinary.publicId)}").exchange().expectStatus().isForbidden
            val response = client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer $token").exchange()
            if (path !in db) {
                // 기능 사용의 Amplitude 값이 한 날짜도 없으면 서버 DB 간편 제작 실패율도 보내지 않는다.
                assertThat(response.expectStatus().isEqualTo(503).expectBody(String::class.java).returnResult().responseBody).doesNotContain("storyCompletion")
                continue
            }
            // DB 값은 그대로 응답하고 Amplitude 플랫폼 값만 준비 중이다.
            val body = mapper.readTree(response.expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
            assertThat(body["pendingDates"].size()).isEqualTo(30); assertThat(body["platformAsOf"].isNull).isTrue()
            when (path) {
                "/users/signups" -> { assertThat(body["daily"][0]["signupsByPlatform"].isNull).isTrue(); assertThat(body["daily"][0]["signups"].has("other")).isFalse() }
                "/conversion/turn-reach" -> { assertThat(body["platforms"].isNull).isTrue(); assertThat(body["segments"].size()).isEqualTo(3); assertThat(body["segment"].asText()).isEqualTo("all") }
                else -> for (field in listOf("chatStarts", "personaChatStarts", "platformTurnsByModel")) assertThat(body["daily"][0][field].isNull).isTrue()
            }
        }
        val charts = mapper.readTree(client.get().uri("/api/v1/admin/dashboard/charts?days=7").header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        val categories = charts["distributions"].toList().associate { it["chartId"].asText() to it["category"].asText() }
        assertThat(categories).containsAllEntriesOf(mapOf("started-as" to "users", "member-chats" to "users", "genre-depth" to "engagement", "turns" to "engagement", "first-message" to "engagement", "creation-mode" to "content", "characters" to "content", "ai-latency" to "ai-quality", "ai-success" to "ai-quality"))
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `지표 HTTP 성공 응답과 기간 플랫폼 퍼널 쿼리를 검증한다`() {
        cleaner.cleanAll()
        val admin=users.save(User(nickname="관리자")); jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)",admin.id)
        val token=jwt.issueAdminToken(admin.publicId)
        server.dispatcher=object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val u=r.requestUrl!!; val format=java.time.format.DateTimeFormatter.BASIC_ISO_DATE
                val from=LocalDate.parse(u.queryParameter("start"),format); val to=LocalDate.parse(u.queryParameter("end"),format)
                val dates=from.datesUntil(to.plusDays(1)).toList()
                val data: Any=when {
                    u.encodedPath.endsWith("/retention") -> mapOf("series" to listOf(mapOf("values" to dates.associate { it.toString() to List(32) { day -> mapOf("count" to if(day==0) 20 else if(day==1) 19 else 5,"outof" to 20,"incomplete" to false) } })))
                    u.encodedPath.endsWith("/funnels") -> listOf(mapOf("cumulativeRaw" to List(u.queryParameterValues("e").size) { 30-it }))
                    u.encodedPath.endsWith("/users") -> mapOf("series" to listOf(listOf(100)))
                    u.encodedPath.endsWith("/sessions/length") -> mapOf("series" to listOf(listOf(4,2,1,1,1,1,0,0,0)), "xValues" to listOf("0s-3s","3s-10s","10s-30s","30s-60s","60s-180s","180s-600s","600s-1800s","1800s-3600s","3600s-86400s"))
                    else -> mapOf("xValues" to dates.map { it.toString() },"series" to listOf(List(dates.size) { if(u.queryParameter("m")=="average" || u.encodedPath.endsWith("/average")) 4.5 else 10 }), "seriesCollapsed" to listOf(listOf(mapOf("value" to 100))))
                }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to data)))
            }
        }
        fun get(path: String)=client.get().uri("/api/v1/admin$path").header("Authorization","Bearer $token").exchange()
        fun body(path: String): tools.jackson.databind.JsonNode {
            (clock as MutableAnalyticsClock).at=clock.instant().plusSeconds(300)
            return mapper.readTree(get(path).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        }
        val period="from=2026-10-19&to=2026-10-21"
        val activity=body("/users/activity?$period")
        assertThat(activity["monthlyActiveUsers"].asLong()).isEqualTo(100)
        assertThat(activity["daily"][0]["activeUsers"]["unknown"].asLong()).isEqualTo(10)
        assertThat(activity["daily"][0]["messagesPerUser"]["all"].isNull).isTrue()
        val retention=body("/users/retention?$period")
        assertThat(retention["platforms"][0]["cohorts"][0]["d1"].asDouble()).isEqualTo(0.25)
        assertThat(retention["platforms"][0]["cohorts"][0]["d7"].isNull).isTrue()
        for (platform in retention["platforms"]) {
            for (field in listOf("d2", "d3", "d4", "d5", "d6")) {
                assertThat(platform["cohorts"][0].has(field)).describedAs(field).isTrue()
                assertThat(platform["summary"].has(field)).describedAs(field).isTrue()
            }
            assertThat(platform["cohorts"][0]["d2"].asDouble()).isEqualTo(0.25)
            assertThat(platform["cohorts"][0]["d3"].asDouble()).isEqualTo(0.25)
            assertThat(platform["cohorts"][0]["d4"].isNull).isTrue()
            assertThat(platform["summary"]["d3"].asDouble()).isEqualTo(0.25)
        }
        assertThat(body("/users/sessions?$period")["daily"][0]["averageSessionSeconds"]["web"].asDouble()).isEqualTo(4.5)
        assertThat(body("/quality/client?$period&platform=android")["platform"].asText()).isEqualTo("android")
        assertThat(body("/quality/client?$period&platform=web&segment=guest")["segment"].asText()).isEqualTo("guest")
        assertThat(body("/users/sessions?$period&segment=member")["segment"].asText()).isEqualTo("member")
        assertThat(retention["cohort"].asText()).isEqualTo("visitor")
        assertThat(body("/users/retention?$period&cohort=signup")["platforms"][0]["cohorts"][0]["users"].asLong()).isEqualTo(40)
        assertThat(body("/users/retention?$period&cohort=first-chat")["cohort"].asText()).isEqualTo("first-chat")
        val lengths=body("/users/session-lengths?$period&segment=member")
        assertThat(lengths["segment"].asText()).isEqualTo("member"); assertThat(lengths["bins"].size()).isEqualTo(9)
        assertThat(lengths["platforms"][0]["sessions"].toList().map { it.asLong() }).containsExactly(12,6,3,3,3,3,0,0,0)
        assertThat(lengths["platforms"][0]["totalSessions"].asLong()).isEqualTo(30); assertThat(lengths["platforms"][0]["medianBin"].asInt()).isEqualTo(1)
        val quality=body("/quality/client?$period")
        assertThat(quality["daily"][0]["users"].has("storyCompleteError")).isFalse()
        assertThat(quality["storyCompletion"].toList().map { it["date"].asText() }).containsExactly("2026-10-19","2026-10-20","2026-10-21")
        assertThat(quality["storyCompletion"][0]["failureRate"].isNull).isTrue()
        val signups = body("/users/signups?$period")["daily"][0]["signupsByPlatform"]
        assertThat(signups.has("unknown")).isFalse()
        assertThat(signups["all"].asLong()).isEqualTo(20)
        val reach = body("/conversion/turn-reach?$period")["platforms"]
        assertThat(reach.toList().map { it["platform"].asText() }).containsExactly("all", "web", "android")
        val options = body("/quality/chat-options?$period")["daily"][0]
        for (field in listOf("chatStarts", "personaChatStarts")) assertThat(options[field].has("unknown")).isFalse()
        for (turns in options["platformTurnsByModel"]) assertThat(turns.has("unknown")).isFalse()
        for ((name,steps) in listOf("web-first-visit" to 6,"android-first-visit" to 2,"story-creation" to 4,"story-detail-chat" to 7,"guest-limit-login" to 3)) {
            val result=body("/conversion/funnels/$name?from=2026-10-21&to=2026-10-21")
            assertThat(result["steps"].size()).isEqualTo(steps)
            assertThat(result["platform"].asText()).isEqualTo(if(name=="android-first-visit") "android" else "web")
        }
        for(path in listOf("/users/activity","/users/retention","/users/sessions","/users/signups","/quality/client","/quality/chat-options","/quality/ai-cost","/conversion/turn-reach")) {
            for(query in listOf("from=2026-10-21","from=2026-02-30&to=2026-10-21","from=2026-10-22&to=2026-10-21","from=2025-10-21&to=2026-10-23","days=7&$period")) get("$path?$query").expectStatus().isBadRequest
        }
        for(path in listOf("/users/activity?days=90","/users/retention?days=30","/users/retention?from=2026-10-23&to=2026-10-23","/users/sessions?days=30","/quality/client?days=90","/quality/client?platform=unknown",
            "/conversion/funnels/unknown","/conversion/funnels/web-first-visit?platform=web","/conversion/funnels/android-first-visit?platform=android","/conversion/funnels/story-creation?storyId=00000000-0000-4000-8000-000000000001",
            "/conversion/funnels/story-detail-chat?storyId=bad","/conversion/funnels/story-creation?platform=all","/conversion/funnels/story-creation?from=2026-09-20&to=2026-10-21",
            "/conversion/turn-reach?segment=visitor","/quality/chat-options?segment=visitor","/conversion/turn-reach?segment=",
            "/users/sessions?segment=visitor","/quality/client?segment=visitor","/users/retention?cohort=member","/users/session-lengths?segment=visitor","/users/session-lengths?days=30")) get(path).expectStatus().isBadRequest
        for(path in listOf("/users/signups","/quality/chat-options","/quality/ai-cost","/conversion/turn-reach")) get("$path?from=2025-10-23&to=2026-10-23").expectStatus().isOk
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs",Long::class.java)).isZero()
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = ["full", "sparse", "empty"])
    fun `세션 길이 HTTP는 실측 응답을 9구간으로 저장하고 빈 날짜도 준비 완료로 응답한다`(shape: String) {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자")); jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        val response = javaClass.getResource("/amplitude/session-length-$shape.json")!!.readText()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody(response) }
        val expected = when (shape) { "full" -> listOf(105L,35,39,14,34,31,16,4,2); "sparse" -> listOf(8L,8,4,4,7,4,1,0,0); else -> List(9) { 0L } }
        val date = LocalDate.parse("2026-10-20")
        val body = mapper.readTree(client.get().uri("/api/v1/admin/users/session-lengths?from=$date&to=$date")
            .header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(body["pendingDates"].isEmpty).isTrue(); assertThat(body["bins"].size()).isEqualTo(9)
        body["platforms"].forEach {
            assertThat(it["sessions"].toList().map { n -> n.asLong() }).isEqualTo(expected)
            assertThat(it["totalSessions"].asLong()).isEqualTo(expected.sum())
            if (shape == "empty") assertThat(it["medianBin"].isNull).isTrue()
            else assertThat(it["medianBin"].asInt()).isEqualTo(if (shape == "full") 1 else 2)
        }
        definitions.sessionLengths().forEach {
            val stored = analyticsValues.daily(it.definition, date, date)[date]!!
            assertThat(stored.value.toList().map { n -> n.asLong() }).isEqualTo(expected); assertThat(stored.finalizedAt).isNotNull()
        }
    }

    @Autowired private lateinit var metricReads: AdminMetricReadRepository
    @Autowired private lateinit var definitions: MetricDefinitions
    @Autowired private lateinit var analyticsValues: AnalyticsValueRepository
    @Test fun `간편 제작 실패율은 그날 받은 완성 요청의 지금 상태로 세고 팀 계정과 진행 중과 스토리라인 생성을 뺀다`() {
        cleaner.cleanAll()
        val member = users.save(User(nickname = "회원")); val team = users.save(User(nickname = "팀"))
        fun request(status: String, owner: Long? = member.id, stage: String = "STORY_COMPLETION", at: String = "2026-10-21T03:00:00Z"): java.util.UUID {
            val id = java.util.UUID.randomUUID()
            jdbc.update("INSERT INTO story_creation_requests(request_id,user_id,device_id_hash,stage,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                id, owner, if (owner == null) "device_hash_0000000000000001" else null, stage, status, java.sql.Timestamp.from(Instant.parse(at)), java.sql.Timestamp.from(Instant.parse(at)))
            return id
        }
        repeat(3) { request("COMPLETED") }; request("FAILED"); request("FAILED", owner = null); request("PENDING")
        request("FAILED", owner = team.id); request("FAILED", stage = "STORYLINE_GENERATION")
        // KST 10-21 00:00 직전은 10-20, 실패 뒤 같은 요청으로 다시 실행해 완료된 행은 완료다.
        request("FAILED", at = "2026-10-20T14:59:59Z")
        val retried = request("FAILED", at = "2026-10-20T15:00:00Z")
        jdbc.update("UPDATE story_creation_requests SET status='COMPLETED' WHERE request_id=?", retried)
        val rows = metricReads.storyCompletions(AnalyticsRange(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-21")), listOf(team.id))
            .associate { LocalDate.ofEpochDay((it["metric_day"] as Number).toLong()) to ((it["completed"] as Number).toLong() to (it["failed"] as Number).toLong()) }
        assertThat(rows).isEqualTo(mapOf(LocalDate.parse("2026-10-20") to (0L to 1L), LocalDate.parse("2026-10-21") to (4L to 2L)))
    }

    @Test fun `간편 제작 실패율은 platform과 segment와 준비 중 날짜와 무관하다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자")); jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        jdbc.update("INSERT INTO story_creation_requests(request_id,user_id,stage,status,created_at,updated_at) VALUES (?,?,'STORY_COMPLETION','FAILED','2026-10-21T03:00:00Z','2026-10-21T03:00:00Z')", java.util.UUID.randomUUID(), admin.id)
        // Amplitude 값은 10-20만 저장돼 있고 대역은 실패해 10-21이 준비 중이다.
        val stored = LocalDate.parse("2026-10-20")
        for (metric in definitions.quality("all") + definitions.quality("web", "guest"))
            analyticsValues.saveDaily(metric.definition, mapOf(stored to AnalyticsValue(mapper.valueToTree(1), clock.instant(), clock.instant())))
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500) }
        val token = jwt.issueAdminToken(admin.publicId)
        fun completion(query: String): tools.jackson.databind.JsonNode {
            (clock as MutableAnalyticsClock).at = clock.instant().plusSeconds(300)
            val body = mapper.readTree(client.get().uri("/api/v1/admin/quality/client?from=2026-10-20&to=2026-10-21$query").header("Authorization", "Bearer $token")
                .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
            assertThat(body["pendingDates"].toList().map { it.asText() }).containsExactly("2026-10-21")
            return body["storyCompletion"]
        }
        val all = completion("")
        assertThat(all.toList().map { it["date"].asText() }).containsExactly("2026-10-20", "2026-10-21")
        assertThat(all[1]["requests"].asLong()).isEqualTo(1); assertThat(all[1]["failed"].asLong()).isEqualTo(1); assertThat(all[1]["failureRate"].asDouble()).isEqualTo(1.0)
        assertThat(completion("&platform=web&segment=guest")).isEqualTo(all)
    }

    @Test fun `DB 지표는 KST 경계 첫 연동 이관 채팅과 누락 원가를 집계한다`() {
        cleaner.cleanAll()
        val start = Instant.parse("2026-10-20T15:00:00Z")
        val end = start.plusSeconds(86400)
        fun user(name: String, at: Instant = start, migrated: Instant? = null) = users.save(User(nickname=name, createdAt=at, migratedAt=migrated))
        val a=user("구글", migrated=start.plusSeconds(100)); val b=user("카카오"); user("연동없음", migrated=start.plusSeconds(100))
        val before=user("이전",start.minusMillis(1)); user("연동없는 이전",start.minusMillis(1)); user("다음",end)
        socials.save(com.knk.manyak.auth.entity.SocialAccount(userId=before.id,provider=com.knk.manyak.auth.entity.SocialProvider.KAKAO,providerUserId="before",connectedAt=start.minusMillis(1)))
        socials.save(com.knk.manyak.auth.entity.SocialAccount(userId=a.id,provider=com.knk.manyak.auth.entity.SocialProvider.GOOGLE,providerUserId="g",connectedAt=start))
        socials.save(com.knk.manyak.auth.entity.SocialAccount(userId=a.id,provider=com.knk.manyak.auth.entity.SocialProvider.KAKAO,providerUserId="kg",connectedAt=start.plusSeconds(1)))
        socials.save(com.knk.manyak.auth.entity.SocialAccount(userId=b.id,provider=com.knk.manyak.auth.entity.SocialProvider.KAKAO,providerUserId="k",connectedAt=start))
        val story=stories.save(com.knk.manyak.story.entity.Story(userId=a.id,title="집계",genre="판타지"))
        fun chat(owner: Long?, turn: Int, model: String="LITE", persona: String?=null, at: Instant=start, deleted: Instant?=null) =
            chats.save(com.knk.manyak.chat.entity.StoryChat(storyId=story.id,userId=owner,currentTurn=turn,modelCode=model,personaNameSnapshot=persona,createdAt=at,deletedAt=deleted))
        chat(null,0); chat(null,5); chat(a.id,10,"AURORA","이름"); chat(b.id,20)
        chat(a.id,100,deleted=start); chat(null,100,at=end)
        val q=com.knk.manyak.admin.dto.AdminChartsRequest().apply { from="2026-10-21"; to="2026-10-21" }
        val signup=reads.signups(q).daily.single()
        // 연동 행이 없는 회원(재가입 예전 계정)은 가입 수, 누계, 게스트 이관에서 모두 빠진다.
        assertThat(signup.signups).isEqualTo(SignupCounts(2,1,1)); assertThat(signup.migratedFromGuest).isEqualTo(1); assertThat(signup.cumulativeSignups).isEqualTo(3)
        val segmented=com.knk.manyak.admin.analytics.AdminSegmentRequest().apply { from="2026-10-21"; to="2026-10-21" }
        val reach=reads.reach(segmented)
        assertThat(reach.segments[0].chats).isEqualTo(4); assertThat(reach.segments[0].reached.map { it.rate }).containsExactly(0.75,0.75,0.75,0.75,0.75,0.5,0.5,0.5,0.25,0.25,0.0,0.0)
        assertThat(reach.segments[1].chats).isEqualTo(2); assertThat(reach.segments[2].chats).isEqualTo(2)
        assertThat(reach.guestToMember).isEqualTo(GuestConversion(1,1,1.0))
        val options=reads.options(segmented).daily.single()
        assertThat(options.chats).isEqualTo(4); assertThat(options.withPersona).isEqualTo(1); assertThat(options.byModel).containsExactlyInAnyOrderEntriesOf(mapOf("LITE" to 3L,"AURORA" to 1L))
        fun ai(feature: String, model: String?, input: Int?, output: Int?, status: String="SUCCEEDED", at: Instant=start) {
            jdbc.update("INSERT INTO ai_call_logs(request_id,caller_service,feature,model,status,input_token_count,output_token_count,created_at,retry_count) VALUES ('fixture','test',?,?,?,?,?,?,0)",feature,model,status,input,output,java.sql.Timestamp.from(at))
        }
        ai("chat_response","priced",1000,500); ai("chat_response","priced",1000,500)
        ai("story_completion","unpriced",3000,1000); ai("chat_response",null,null,null); ai("chat_response","priced",0,null)
        ai("chat_response","priced",999999,999999,"FAILED"); ai("chat_response","priced",999999,999999,at=end)
        langfuseCosts(Triple("채팅 턴", "priced", "0.004"), Triple("채팅 선택지", "priced", "0.001"),
            Triple("스토리라인 생성", "priced", "0.004"), Triple("스토리 컴파일", "priced", "0.001"), Triple("스토리 컴파일", "unpriced", "0"))
        val cost=costs.aiCost(q); val items=cost.daily.single().byFeature
        val priced=items.first { it.model=="priced" && it.feature=="chat_response" }
        assertThat(priced.calls).isEqualTo(3); assertThat(priced.callsWithoutTokens).isEqualTo(1)
        assertThat(priced.costKrw).isEqualByComparingTo("5.6")
        assertThat(items.first { it.model==null }.callsWithoutTokens).isEqualTo(1)
        assertThat(cost.totals.costPerTurnKrw).isEqualByComparingTo("1.75"); assertThat(cost.totals.costPerStoryKrw).isEqualByComparingTo("7")
        assertThat(cost.unpricedModels).containsExactly("unpriced")
        ai("choice_generation","priced",1000,0); ai("storyline_generation","priced",2000,1000); ai("story_completion","priced",1000,0)
        val combined=costs.aiCost(q)
        assertThat(combined.totals.costKrw).isEqualByComparingTo("14")
        assertThat(combined.totals.costPerTurnKrw).isEqualByComparingTo("1.75")
        assertThat(combined.totals.costPerStoryKrw).isEqualByComparingTo("3.5")
        prepareLangfuse()
        q.from="2026-10-19"; q.to="2026-10-20"; segmented.from=q.from; segmented.to=q.to
        assertThat(reads.options(segmented).daily).hasSize(2); assertThat(reads.options(segmented).daily.sumOf { it.chats }).isZero()
        assertThat(reads.reach(segmented).segments.all { it.reached.all { p -> p.rate==0.0 } }).isTrue()
        assertThat(costs.aiCost(q).totals.costPerTurnKrw).isNull()
    }

}
@org.springframework.boot.test.context.TestConfiguration
class AdminMetricsTestClock {
    @org.springframework.context.annotation.Bean fun metricsClock(): Clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
}
