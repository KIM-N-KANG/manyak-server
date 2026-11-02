package com.knk.manyak.admin

import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.*
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/** 앱 datasource와 분리한 실제 PostgreSQL: Flyway 및 운영 SQL의 바인딩·집계·정렬을 검증한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminReadPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "admin-read-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var reads: AdminReadRepository
    private lateinit var content: AdminContentRepository
    private lateinit var charts: AdminChartsRepository
    private var userId: Long = 0
    private val memberId = UUID.randomUUID()
    private val storyId = UUID.randomUUID()
    private val chatId = UUID.randomUUID()
    private val orderId = UUID.randomUUID()
    private val now = Instant.parse("2026-10-21T03:00:00Z")
    private val start = Instant.parse("2026-10-20T15:00:00Z")

    @BeforeAll fun start() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "admin-read-test")
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        jdbc = JdbcTemplate(ds)
        val named = NamedParameterJdbcTemplate(jdbc)
        reads = AdminReadRepository(named, ObjectMapper()); content = AdminContentRepository(named); charts = AdminChartsRepository(named)
        userId = jdbc.queryForObject("INSERT INTO users(public_id,nickname,created_at) VALUES (?, 'pg 검색 회원', '2026-10-21T00:00:00Z') RETURNING id", Long::class.java, memberId)!!
        jdbc.update("INSERT INTO admin_accounts(user_id) VALUES (?)", userId)
        val sid = jdbc.queryForObject("INSERT INTO stories(public_id,user_id,title,genre,created_at) VALUES (?,?,'pg 검색 스토리','판타지, 미스터리','2026-10-21T00:00:00Z') RETURNING id", Long::class.java, storyId, userId)!!
        val session = jdbc.queryForObject("INSERT INTO story_creation_sessions(story_id,status,created_at) VALUES (?,'STORY_CREATED','2026-10-21T00:00:00Z') RETURNING id", Long::class.java, sid)!!
        jdbc.update("INSERT INTO story_creation_characters(creation_session_id,role,name,gender,sort_order) VALUES (?, 'SUPPORTING_CHARACTER', NULL, NULL, 1), (?, 'SUPPORTING_CHARACTER', ?, NULL, 2), (?, 'SUPPORTING_CHARACTER', '이름', NULL, 3), (?, 'SUPPORTING_CHARACTER', NULL, 'FEMALE', 4)",
            session, session, " \t\n　", session, session)
        val tagged = jdbc.queryForObject("INSERT INTO story_creation_characters(creation_session_id,role,sort_order) VALUES (?, 'SUPPORTING_CHARACTER', 5) RETURNING id", Long::class.java, session)!!
        for (source in listOf("PREDEFINED", "CUSTOM")) {
            val tag = jdbc.queryForObject("INSERT INTO story_creation_tags(tag_type,name,tag_source,normalized_name,sort_order) VALUES ('SUPPORTING_CHARACTER', ?, ?, ?, 0) RETURNING id", Long::class.java, source, source, source.lowercase())!!
            jdbc.update("INSERT INTO story_creation_session_tags(creation_session_id,character_id,tag_id) VALUES (?, ?, ?)", session, tagged, tag)
        }
        val cid = jdbc.queryForObject("INSERT INTO story_chats(public_id,user_id,story_id,current_turn,created_at) VALUES (?,?,?,4,'2026-10-21T00:00:00Z') RETURNING id", Long::class.java, chatId, userId, sid)!!
        jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order,created_at) VALUES (?,'USER','fixture',1,'2026-10-21T00:00:30Z'),(?,'ASSISTANT','fixture',2,'2026-10-21T00:01:00Z')", cid, cid)
        jdbc.update("INSERT INTO credit_transactions(user_id,amount,reason,created_at) VALUES (?,250,'ATTENDANCE_REWARD','2026-10-21T00:00:01Z')", userId)
        jdbc.update("INSERT INTO credit_transactions(user_id,amount,reason,ref_type,ref_id,created_at) VALUES (?,-20,'CHAT_TURN','CHAT_TURN',?,'2026-10-21T00:02:00Z')", userId, cid)
        jdbc.update("INSERT INTO credit_orders(public_id,user_id,product_id,provider,status,price_krw,credit_amount,completed_at) VALUES (?,?,'if_5000','GROBLE','COMPLETED',4800,5000,'2026-10-21T00:01:00Z')", orderId, userId)
        jdbc.update("INSERT INTO ai_call_logs(request_id,caller_service,feature,status,latency_ms,chat_id,story_id,created_at) VALUES ('pg','test','chat_response','SUCCEEDED',1000,?,?,'2026-10-21T00:01:00Z')", chatId, sid)
        jdbc.update("INSERT INTO admin_audit_logs(public_id,admin_user_id,category,action,target_type,target_id,request_id,created_at) VALUES (gen_random_uuid(),?,'ACCESS','MEMBER_VIEWED','MEMBER',?,'pg','2026-10-21T00:01:00Z')", userId, memberId.toString())
    }
    @AfterAll fun stop() { pg.stop() }

    @Test fun `실제 PostgreSQL 누적 턴은 삭제와 이관 채팅을 포함하고 재생성은 제외한다`() {
        val owner = jdbc.queryForObject("INSERT INTO users(public_id,nickname) VALUES (?,'누적 턴 PG') RETURNING id", Long::class.java, UUID.randomUUID())!!
        val sid = jdbc.queryForObject("SELECT id FROM stories WHERE public_id=?", Long::class.java, storyId)!!
        try {
            assertThat(reads.memberTurnCount(owner)).isZero()
            for ((index, count) in listOf(2, 3, 4).withIndex()) {
                val cid = jdbc.queryForObject("""INSERT INTO story_chats(public_id,user_id,story_id,current_turn,deleted_at)
                    VALUES (?,?,?,99,?) RETURNING id""", Long::class.java, UUID.randomUUID(),
                    if (index == 2) null else owner, sid, if (index == 1) now.atOffset(java.time.ZoneOffset.UTC) else null)!!
                repeat(count) { n ->
                    val mid = jdbc.queryForObject("""INSERT INTO story_messages(chat_id,role,content,message_order)
                        VALUES (?,'ASSISTANT','fixture',?) RETURNING id""", Long::class.java, cid, n + 1)!!
                    if (n == 0) repeat(2) { v -> jdbc.update("""INSERT INTO story_message_versions(message_id,version_number,content,choices)
                        VALUES (?,?,'fixture','[]')""", mid, v + 1) }
                }
                jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order) VALUES (?,'USER','fixture',?)", cid, count + 1)
                if (index == 2) jdbc.update("UPDATE story_chats SET user_id=? WHERE id=?", owner, cid)
            }
            assertThat(reads.memberTurnCount(owner)).isEqualTo(9)
            assertThat(reads.memberTurnCount(userId)).isEqualTo(1)
            val ownerId = jdbc.queryForObject("SELECT public_id FROM users WHERE id=?", UUID::class.java, owner)!!
            val listed = reads.members(AdminMembersRequest(), AdminPagination(1, 1, "u.created_at desc"), ownerId).items.single()
            assertThat(listed.turnCount).isEqualTo(9)
            assertThat(listed.periodTurnCount).isNull()
        } finally {
            jdbc.update("DELETE FROM story_chats WHERE user_id=?", owner)
            jdbc.update("DELETE FROM users WHERE id=?", owner)
        }
    }

    private fun withMemberPlanFixture(block: () -> Unit) {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_story_chats_user_created ON story_chats (user_id, created_at)")
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_story_messages_admin_chat_role_created ON story_messages (chat_id, role, created_at)")
        val ids = jdbc.queryForList("""INSERT INTO users(public_id,nickname,created_at)
            SELECT gen_random_uuid(), '회원 실행 계획 ' || n, TIMESTAMPTZ '2026-10-22 00:00:00+00' + n * INTERVAL '1 second'
            FROM generate_series(1,40) AS seq(n) RETURNING id""", Long::class.java)
        val named = NamedParameterJdbcTemplate(jdbc)
        val params = mapOf("ids" to ids, "story" to jdbc.queryForObject("SELECT id FROM stories WHERE public_id=?", Long::class.java, storyId)!!)
        try {
            // 회원마다 20개 채팅(삭제/미삭제 각 10개), 채팅마다 USER/ASSISTANT 각 30개.
            named.update("""INSERT INTO story_chats(public_id,user_id,story_id,created_at,deleted_at)
                SELECT gen_random_uuid(), u.id, :story, TIMESTAMPTZ '2026-10-21 00:00:00+00' + n * INTERVAL '1 hour',
                    CASE WHEN n % 2 = 0 THEN TIMESTAMPTZ '2026-10-23 00:00:00+00' ELSE NULL END
                FROM users u CROSS JOIN generate_series(1,20) AS seq(n) WHERE u.id IN (:ids)""", params)
            named.update("""INSERT INTO story_messages(chat_id,role,content,message_order,created_at)
                SELECT c.id, CASE WHEN n % 2 = 0 THEN 'ASSISTANT' ELSE 'USER' END, '실행 계획 fixture', n,
                    c.created_at + n * INTERVAL '1 second'
                FROM story_chats c CROSS JOIN generate_series(1,60) AS seq(n) WHERE c.user_id IN (:ids)""", params)
            for (table in listOf("users", "story_chats", "story_messages")) jdbc.execute("ANALYZE $table")
            block()
        } finally {
            named.update("DELETE FROM story_chats WHERE user_id IN (:ids)", params)
            named.update("DELETE FROM users WHERE id IN (:ids)", params)
            for (table in listOf("users", "story_chats", "story_messages")) jdbc.execute("ANALYZE $table")
        }
    }

    @Test fun `회원 턴 집계는 정렬 여부와 무관하게 응답 페이지 ID로 묶고 기본 정렬 전에 실행하지 않는다`() = withMemberPlanFixture {
        val plans = mutableListOf<String>()
        for ((sort, column) in listOf("createdAt" to "u.created_at", "turnCount" to "turn_count")) {
            val traced = org.mockito.Mockito.spy(NamedParameterJdbcTemplate(jdbc))
            val repository = AdminReadRepository(traced, ObjectMapper())
            val response = repository.members(AdminMembersRequest().apply { this.sort = sort }, AdminPagination(1, 1, "$column desc"))
            assertThat(response.items).hasSize(1)
            val queries = org.mockito.Mockito.mockingDetails(traced).invocations.filter {
                it.method.name == "query" && it.arguments.size == 3 && it.arguments[0] is String && it.arguments[1] is Map<*, *>
            }.map { invocation ->
                @Suppress("UNCHECKED_CAST")
                (invocation.arguments[0] as String) to (invocation.arguments[1] as Map<String, Any>)
            }
            val pageQuery = queries.single { it.first.contains("LIMIT :size") }
            assertThat(pageQuery.first.contains("m.role='ASSISTANT'")).isEqualTo(sort == "turnCount")
            val turnQuery = queries.single { it.first.contains("GROUP BY c.user_id") }
            assertThat(turnQuery.second["memberIds"] as Collection<*>).hasSize(1)
            for ((sql, params) in queries) {
                val explanation = NamedParameterJdbcTemplate(jdbc).queryForList("EXPLAIN (ANALYZE, BUFFERS) $sql", params)
                    .joinToString("\n") { it.values.first().toString() }
                plans += "$sort\n$sql\n$explanation\n"
            }
        }
        val output = java.nio.file.Path.of("build/reports/admin-member-turn-plans.txt")
        java.nio.file.Files.createDirectories(output.parent)
        java.nio.file.Files.writeString(output, plans.joinToString("\n"))
    }

    @Test fun `실제 PostgreSQL 제출본 동률은 UUID 오름차순이며 페이지 누락이 없다`() {
        val ids=listOf("00000000-0000-4000-8000-000000000001","ffffffff-0000-4000-8000-000000000001","80000000-0000-4000-8000-000000000001","7fffffff-0000-4000-8000-000000000001").map(UUID::fromString)
        val repository=com.knk.manyak.story.submission.AdminSubmissionReadRepository(NamedParameterJdbcTemplate(jdbc),ObjectMapper())
        try {
            ids.forEach { jdbc.update("""INSERT INTO story_submissions(public_id,user_id,kind,payload,input_form,status,created_at)
                VALUES (?,?,'CREATE','{"title":"동률 PG"}','{}','PENDING',?)""",it,userId,now.atOffset(java.time.ZoneOffset.UTC)) }
            for(sort in listOf("createdAt","decidedAt")) for(order in listOf("asc","desc")) {
                val actual=(1..2).flatMap { number -> repository.list(com.knk.manyak.story.submission.AdminSubmissionListRequest().apply { q="동률 PG";size=2;page=number;this.sort=sort;this.order=order }).items }.map { it.submissionId.toString() }
                assertThat(actual).containsExactlyElementsOf(ids.map { it.toString() }.sorted())
            }
        } finally { ids.forEach { jdbc.update("DELETE FROM story_submissions WHERE public_id=?",it) } }
    }

    @Test fun `실제 PostgreSQL 제출본 JSON 제목과 저장 위반 요약 및 KST 범위를 조회한다`() {
        val repository=com.knk.manyak.story.submission.AdminSubmissionReadRepository(NamedParameterJdbcTemplate(jdbc),ObjectMapper())
        val id=UUID.randomUUID(); val before=UUID.randomUUID()
        val title="""제목 "인용" %_"""
        try {
            for((key,at) in listOf(before to start.minusNanos(1000),id to start)) jdbc.update("""INSERT INTO story_submissions(public_id,user_id,kind,payload,input_form,status,issues,created_at,decided_at)
                VALUES (?,?,'CREATE',CAST(? AS jsonb),'{}','REJECTED',CAST(? AS jsonb),?,?)""",key,userId,
                ObjectMapper().writeValueAsString(mapOf("title" to title)), """[{"path":"title","type":"TEXT","rule":"DRUGS","reason":"첫 이유"}]""",at.atOffset(java.time.ZoneOffset.UTC),now.atOffset(java.time.ZoneOffset.UTC))
            val result=repository.list(com.knk.manyak.story.submission.AdminSubmissionListRequest().apply { from="2026-10-21";to="2026-10-21";memberId=this@AdminReadPostgresTests.memberId;q="인용";sort="decidedAt";order="asc" })
            assertThat(result.items.map { it.submissionId }).containsExactly(id)
            assertThat(result.items.single().story.title).isEqualTo(title)
            assertThat(result.items.single().issueRules).containsExactly("DRUGS")
            assertThat(result.items.single().firstIssueReason).isEqualTo("첫 이유")
            assertThat(repository.summary(id)!!.owner.memberId).isEqualTo(memberId)
        } finally { jdbc.update("DELETE FROM story_submissions WHERE public_id IN (?,?)",id,before) }
    }

    @Test fun `실제 PostgreSQL의 회원 집계 필터 정렬과 원장 누계 및 출석 날짜를 조회한다`() {
        val request = AdminMembersRequest().apply {
            q = "pg 검색"; chatBucket = 0; chatFrom = "2026-10-21"; creditDirection = "spent"; creditDate = "2026-10-21"; attendanceDate = "2026-10-21"
        }
        for (sort in listOf("last_active_at", "credits_spent", "paid_krw", "chat_count")) {
            val result = reads.members(request, AdminPagination(1, 20, "$sort asc NULLS LAST"))
            assertThat(result.totalElements).isEqualTo(1)
            assertThat(result.items.single().paidKrw).isEqualTo(4800)
            assertThat(result.items.single().creditsSpent).isEqualTo(20)
        }
        assertThat(reads.transactions(userId, AdminPagination(1, 1, "created_at desc")).items.single().balanceAfter).isEqualTo(230)
        assertThat(reads.attendance(userId, AdminPagination(1, 20, "attendance_date desc")).items.single().date.toString()).isEqualTo("2026-10-21")
    }
    @Test fun `실제 PostgreSQL의 콘텐츠 상관 집계와 AI 조건 및 결제 전후 조회를 실행한다`() {
        val stories = content.stories(AdminStoriesRequest().apply { q = storyId.toString(); chatFrom = "2026-10-21" }, AdminPagination(1, 20, "total_turns desc"), userId)
        assertThat(stories.items.single().scope).isEqualTo("original")
        assertThat(stories.items.single().totalTurns).isEqualTo(4)
        val chats = content.chats(AdminChatsRequest().apply { aiFeature = "chat_response"; aiStatus = "FINISHED"; aiFrom = "2026-10-21"; hasFirstMessage = true; turnDate = "2026-10-21" }, AdminPagination(1, 20, "credits_spent desc"))
        assertThat(chats.items.single().firstMessageSeconds).isEqualTo(30)
        assertThat(chats.items.single().creditsSpent).isEqualTo(20)
        assertThat(content.retention(storyId).first().rate).isEqualTo(1.0)
        assertThat(reads.payments(AdminPaymentsRequest().apply { q = orderId.toString(); this.memberId = this@AdminReadPostgresTests.memberId }, AdminPagination(1, 20, "o.completed_at desc")).totalElements).isEqualTo(1)
        assertThat(reads.payment(orderId)!!.priceKrw).isEqualTo(4800)
        val usage = reads.paymentUsage(userId, reads.firstPaidAt(memberId))
        assertThat(usage.second.creditsSpent).isEqualTo(20)
        assertThat(reads.auditLogs(AdminAuditLogsRequest().apply { q = memberId.toString(); category = com.knk.manyak.admin.entity.AdminAuditCategory.ACCESS; from = "2026-10-21" }, AdminPagination(1, 20, "u.nickname asc")).totalElements).isEqualTo(1)
    }
    @Test fun `실제 PostgreSQL에서 전체 이프 거래의 방향과 KST 날짜 및 회원 요약을 조회한다`() {
        val page = reads.creditTransactions(AdminCreditTransactionsRequest().apply {
            direction = "spent"; date = "2026-10-21"
        }, AdminPagination(1, 20, "t.created_at desc"))
        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.items.single().amount).isEqualTo(-20)
        assertThat(page.items.single().member.memberId).isEqualTo(memberId)
        assertThat(page.items.single().member.isAdmin).isTrue()
        assertThat(reads.creditTransactions(AdminCreditTransactionsRequest().apply {
            date = "2026-10-20"
        }, AdminPagination(1, 20, "t.created_at desc")).totalElements).isZero()
    }

    @Test fun `실제 PostgreSQL도 빈 칸을 제외하고 특징 태그를 가진 인물을 한 번만 센다`() {
        val page = content.stories(AdminStoriesRequest().apply { supportingCharacterCount = 3 }, AdminPagination(1, 20, "s.id asc"), null)
        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.items.single().supportingCharacterCount).isEqualTo(3)
        val point = charts.distributions(start, now, emptyList(), null)[7].single()
        assertThat((point["bucket"] as Number).toInt()).isEqualTo(3)
        assertThat((point["n"] as Number).toLong()).isEqualTo(page.totalElements)
    }
    @Test fun `실제 PostgreSQL에서 일별 KST 집계와 분포 아홉 쿼리를 실행한다`() {
        val daily = charts.daily(start, now, emptyList()).values.single()
        assertThat(daily["attendance"]).isEqualTo(1)
        assertThat(daily["turns"]).isEqualTo(1)
        assertThat(daily["revenue"]).isEqualTo(4800)
        val raw = charts.distributions(start, now, emptyList(), null)
        assertThat(raw).hasSize(9)
        assertThat(raw[8].associate { it["label"] to (it["n"] as Number).toLong() }).containsKey("SIMPLE")
        assertThat((raw[2].single { it["label"] == "MEMBER" }["users"] as Number).toLong()).isEqualTo(1)
        assertThat((raw[4].single()["average"] as Number).toDouble()).isEqualTo(30.0)
        assertThat((raw[5].single()["metric_value"] as Number).toDouble()).isEqualTo(1.0)
        assertThat((raw[6].single()["metric_value"] as Number).toDouble()).isEqualTo(100.0)
        assertThat(charts.daily(start, now, listOf(userId)).values.sumOf { it["chats"] ?: 0 }).isZero()
    }
    @Test fun `실제 PostgreSQL도 턴 0부터 6과 이후 경계의 집계 및 드릴다운 구간이 같다`() {
        val publicId = UUID.randomUUID()
        val sid = jdbc.queryForObject("INSERT INTO stories(public_id,title,genre,created_at) VALUES (?,'턴 경계 PG','판타지','2026-10-20T00:00:00Z') RETURNING id", Long::class.java, publicId)!!
        val values = (0..7).toList() + listOf(9, 10, 14, 15, 19, 20, 29, 30, 49, 50, 51, Int.MAX_VALUE)
        values.forEach { turn -> jdbc.update("INSERT INTO story_chats(public_id,story_id,current_turn,persona_name_snapshot,model_code,created_at) VALUES (?,?,?,NULL,'LITE','2026-10-20T00:00:00Z')", UUID.randomUUID(), sid, turn) }
        val bounds = listOf(0..0, 1..1, 2..2, 3..3, 4..4, 5..5, 6..6, 7..9, 10..14, 15..19, 20..29, 30..49, 50..Int.MAX_VALUE)
        val raw = charts.distributions(Instant.parse("2026-10-19T15:00:00Z"), Instant.parse("2026-10-20T03:00:00Z"), emptyList(), null)[1]
            .associate { (it["bucket"] as Number).toInt() to (it["n"] as Number).toLong() }
        assertThat(raw.keys).containsExactlyInAnyOrderElementsOf((0..12).toList())
        bounds.forEachIndexed { index, range ->
            val expected = values.filter { it in range }
            assertThat(raw[index]).isEqualTo(expected.size.toLong())
            val page = content.chats(AdminChatsRequest().apply { storyId = publicId; turnBucket = index }, AdminPagination(1, 100, "c.id asc"))
            assertThat(page.totalElements).isEqualTo(expected.size.toLong())
            assertThat(page.items.map { it.turnCount }).containsExactlyInAnyOrderElementsOf(expected)
        }
    }

}
