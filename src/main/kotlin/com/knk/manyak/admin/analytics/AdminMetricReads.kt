package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.dto.AdminDateRange
import com.knk.manyak.admin.repository.AdminQuery
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.*

class AdminSegmentRequest : AdminChartsRequest() { var segment: String? = null }
data class SignupCounts(val total: Long, val google: Long, val kakao: Long)
data class SignupDay(val date: LocalDate, val signups: SignupCounts, val cumulativeSignups: Long, val migratedFromGuest: Long, val signupsByPlatform: PlatformCounts?)
data class SignupResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val platformAsOf: Instant?, val pendingDates: List<LocalDate>, val daily: List<SignupDay>)
data class ReachPoint(val turn: Int, val chats: Long, val rate: Double)
data class ReachSegment(val segment: String, val chats: Long, val reached: List<ReachPoint>)
data class GuestConversion(val chatsReached5: Long, val chatsReached6: Long, val rate: Double)
data class PlatformReach(val platform: String, val chats: Long, val reached: List<ReachPoint>)
data class TurnReachResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val segment: String, val platformAsOf: Instant?, val pendingDates: List<LocalDate>,
    val segments: List<ReachSegment>, val guestToMember: GuestConversion, val platforms: List<PlatformReach>?)
data class ChatOptionDay(val date: LocalDate, val chats: Long, val withPersona: Long, val byModel: Map<String, Long>, val turnsByModel: Map<String, Long>,
    val chatStarts: PlatformCounts?, val personaChatStarts: PlatformCounts?, val platformTurnsByModel: Map<String, PlatformCounts>?)
data class ChatOptionResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val segment: String, val platformAsOf: Instant?, val pendingDates: List<LocalDate>, val daily: List<ChatOptionDay>)
data class AiCostItem(val feature: String, val model: String?, val calls: Long, val callsWithoutTokens: Long, val inputTokens: Long, val outputTokens: Long, val costKrw: BigDecimal?)
data class AiCostDay(val date: LocalDate, val byFeature: List<AiCostItem>)
data class AiCostTotals(val costKrw: BigDecimal, val costPerTurnKrw: BigDecimal?, val costPerStoryKrw: BigDecimal?)
data class AiCostResponse(val asOf: Instant?, val from: LocalDate, val to: LocalDate, val currency: String, val chatImageUnitCostKrw: BigDecimal, val daily: List<AiCostDay>, val totals: AiCostTotals, val unpricedModels: List<String>, val pendingDates: List<LocalDate>, val usdKrwRate: BigDecimal, val unmapped: List<AiCostUnmapped>, val expiredDates: List<LocalDate>, val retentionDays: Int, val retentionStartDate: LocalDate)
data class AiCostUnmapped(val traceName: String, val costKrw: BigDecimal)

@Repository
class AdminMetricReadRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private fun day(column: String) = "CAST(FLOOR((EXTRACT(EPOCH FROM $column)+32400)/86400) AS BIGINT)"
    private fun params(range: AnalyticsRange, excluded: List<Long>) = mapOf("start" to range.from.atStartOfDay(AdminDateRange.KST).toOffsetDateTime(),
        "end" to range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toOffsetDateTime(), "excluded" to excluded)
    private fun exclude(column: String, excluded: List<Long>) = if (excluded.isEmpty()) "" else " AND ($column IS NULL OR $column NOT IN (:excluded))"
    // 연동 행이 하나도 없는 회원은 재가입으로 연동을 새 계정에 넘긴 예전 계정이라 뺀다(탈퇴 tombstone 행도 행으로 센다).
    fun signupsBefore(range: AnalyticsRange, excluded: List<Long>): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM users u WHERE u.created_at < :start AND EXISTS (SELECT 1 FROM social_accounts a WHERE a.user_id=u.id) ${exclude("u.id", excluded)}",
        params(range, excluded), Long::class.java) ?: 0L
    fun signups(range: AnalyticsRange, excluded: List<Long>): List<Map<String, Any?>> = jdbc.queryForList("""
        SELECT ${day("u.created_at")} AS metric_day, COUNT(*) AS total,
        SUM(CASE WHEN s.provider='GOOGLE' THEN 1 ELSE 0 END) AS google,
        SUM(CASE WHEN s.provider='KAKAO' THEN 1 ELSE 0 END) AS kakao,
        SUM(CASE WHEN u.migrated_at IS NOT NULL THEN 1 ELSE 0 END) AS migrated
        FROM users u JOIN (SELECT user_id, provider, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY connected_at, id) AS seq FROM social_accounts) s ON s.user_id=u.id AND s.seq=1
        WHERE u.created_at>=:start AND u.created_at<:end ${exclude("u.id", excluded)} GROUP BY ${day("u.created_at")} ORDER BY metric_day
        """, params(range, excluded))
    fun reach(range: AnalyticsRange, excluded: List<Long>): List<Map<String, Any?>> = jdbc.queryForList("""
        SELECT CASE WHEN c.user_id IS NULL THEN 'guest' ELSE 'member' END AS segment, COUNT(*) AS chats,
        ${AdminQuery.turnReachThresholds.joinToString(",") { "SUM(CASE WHEN c.current_turn >= $it THEN 1 ELSE 0 END) AS turn_$it" }},
        SUM(CASE WHEN u.migrated_at IS NOT NULL AND c.created_at<u.migrated_at AND c.current_turn>=5 THEN 1 ELSE 0 END) AS migrated_5,
        SUM(CASE WHEN u.migrated_at IS NOT NULL AND c.created_at<u.migrated_at AND c.current_turn>=6 THEN 1 ELSE 0 END) AS migrated_6
        FROM story_chats c LEFT JOIN users u ON u.id=c.user_id WHERE c.created_at>=:start AND c.created_at<:end AND c.deleted_at IS NULL ${exclude("c.user_id", excluded)}
        GROUP BY CASE WHEN c.user_id IS NULL THEN 'guest' ELSE 'member' END
        """, params(range, excluded))
    fun options(range: AnalyticsRange, excluded: List<Long>): List<Map<String, Any?>> = jdbc.queryForList("""
        SELECT ${day("c.created_at")} AS metric_day, c.model_code AS model, COUNT(*) AS chats,
        SUM(CASE WHEN c.persona_name_snapshot IS NOT NULL THEN 1 ELSE 0 END) AS persona
        FROM story_chats c WHERE c.created_at>=:start AND c.created_at<:end AND c.deleted_at IS NULL ${exclude("c.user_id", excluded)}
        GROUP BY ${day("c.created_at")}, c.model_code ORDER BY metric_day, model
        """, params(range, excluded))
    // 활성 응답의 청구 모델로 센다. 채팅 모델 변경은 과거 턴을 재분류하지 않으며 기록 전 행은 추정하지 않는다.
    // 판본은 별도 테이블이므로 제외한다. 채팅의 생성일과 삭제 여부는 제한하지 않는다.
    fun turnsByModel(range: AnalyticsRange, excluded: List<Long>): List<Map<String, Any?>> = jdbc.queryForList("""
        SELECT ${day("m.created_at")} AS metric_day, COALESCE(m.model_code, 'UNRECORDED') AS model, COUNT(*) AS turns
        FROM story_messages m JOIN story_chats c ON c.id=m.chat_id
        WHERE m.created_at>=:start AND m.created_at<:end AND m.role='ASSISTANT' ${exclude("c.user_id", excluded)}
        GROUP BY ${day("m.created_at")}, COALESCE(m.model_code, 'UNRECORDED') ORDER BY metric_day, model
        """, params(range, excluded))
    fun aiCost(range: AnalyticsRange): List<Map<String, Any?>> = jdbc.queryForList("""
        SELECT ${day("created_at")} AS metric_day, feature, model, COUNT(*) AS calls,
        SUM(CASE WHEN input_token_count IS NULL OR output_token_count IS NULL THEN 1 ELSE 0 END) AS missing,
        SUM(COALESCE(input_token_count,0)) AS input, SUM(COALESCE(output_token_count,0)) AS output
        FROM ai_call_logs WHERE created_at>=:start AND created_at<:end AND status='SUCCEEDED'
        AND feature IN ('chat_response','choice_generation','chat_image','storyline_generation','story_completion')
        GROUP BY ${day("created_at")}, feature, model ORDER BY metric_day, feature, model
        """, params(range, emptyList()))
    fun modelCodes(): List<String> = jdbc.queryForList("SELECT code FROM chat_models ORDER BY code", emptyMap<String, Any>(), String::class.java)
}

@Service
class AdminMetricReadService(private val repository: AdminMetricReadRepository, private val dashboard: AdminDashboardProperties,
    private val definitions: MetricDefinitions, private val snapshots: AnalyticsSnapshots, private val clock: Clock = Clock.systemUTC()) {
    /** Amplitude 플랫폼 값. 받지 못한 날짜는 null과 pendingDates로 알리고 DB 값은 그대로 응답한다. */
    private class PlatformValues(private val result: AnalyticsSnapshotResult) {
        val ready = result.readyDates()
        val asOf = if (ready.isEmpty()) null else result.asOf(ready.flatMap { result.values(it) })
        val pending = result.pendingDates()
        /** web, android, unknown 세 묶음을 날짜들에서 더하고 전체는 그 합이다. */
        fun counts(groups: List<List<DailyAnalyticsMetric>>, dates: List<LocalDate> = ready): PlatformCounts {
            val (web, android, unknown) = groups.map { group -> dates.fold(0L) { sum, date -> group.fold(sum) { s, m -> Math.addExact(s, result.value(m, date)!!.value.asLong()) } } }
            return PlatformCounts(Math.addExact(Math.addExact(web, android), unknown), web, android, unknown)
        }
        fun day(date: LocalDate, groups: List<List<DailyAnalyticsMetric>>) = if (date in ready) counts(groups, listOf(date)) else null
    }
    private fun segment(query: AdminSegmentRequest) = analyticsSegment(query.segment)
    // 대시보드 분석 지표처럼 5초 안에 못 받은 플랫폼 값은 준비 중으로 두고 DB 값을 바로 응답한다.
    private fun platformValues(endpoint: String, range: AnalyticsRange, metrics: List<DailyAnalyticsMetric>) =
        PlatformValues(snapshots.load(endpoint, range, metrics, timeout = Duration.ofSeconds(5)))
    private fun range(query: AdminChartsRequest) = query.resolve(clock).let { AnalyticsRange(it.from, it.to) }
    private fun Map<String, Any?>.n(key: String) = (this[key] as Number?)?.toLong() ?: 0L
    private fun Map<String, Any?>.date() = LocalDate.ofEpochDay(n("metric_day"))
    fun signups(query: AdminChartsRequest): SignupResponse {
        val range = range(query); val rows = repository.signups(range, dashboard.excludedUserIds).associateBy { it.date() }
        var cumulative = repository.signupsBefore(range, dashboard.excludedUserIds)
        val metrics = definitions.signupPlatforms(); val platform = platformValues("users/signups", range, metrics)
        return SignupResponse(clock.instant(), range.from, range.to, platform.asOf, platform.pending, range.dates().map { date ->
            val row = rows[date] ?: emptyMap(); val total = row.n("total"); cumulative = Math.addExact(cumulative, total)
            SignupDay(date, SignupCounts(total, row.n("google"), row.n("kakao")), cumulative, row.n("migrated"), platform.day(date, metrics.chunked(2)))
        })
    }
    fun reach(query: AdminSegmentRequest): TurnReachResponse {
        val range = range(query); val segment = segment(query); val rows = repository.reach(range, dashboard.excludedUserIds)
        fun ratio(n: Long, d: Long) = if (d == 0L) 0.0 else n.toDouble()/d
        val segments = listOf("all", "guest", "member").map { segment ->
            val group = rows.filter { segment == "all" || it["segment"] == segment }; val count = group.sumOf { it.n("chats") }
            ReachSegment(segment, count, AdminQuery.turnReachThresholds.map { turn -> val n = group.sumOf { it.n("turn_$turn") }; ReachPoint(turn, n, ratio(n, count)) })
        }
        val five = rows.sumOf { it.n("migrated_5") }; val six = rows.sumOf { it.n("migrated_6") }
        val metrics = definitions.turnReach(segment); val platform = platformValues("conversion/turn-reach", range, metrics)
        // 기준 턴마다 플랫폼 묶음. 분모는 1턴 성공 이벤트 수라 범위 전에 시작한 채팅 때문에 1을 넘을 수 있다.
        val byTurn = AdminQuery.turnReachThresholds.indices.map { i -> platform.counts(metrics.chunked(AdminQuery.turnReachThresholds.size).map { listOf(it[i]) }) }
        val platforms = if (platform.ready.isEmpty()) null else listOf("all", "web", "android", "unknown").map { name ->
            fun pick(c: PlatformCounts) = when (name) { "all" -> c.all; "web" -> c.web; "android" -> c.android; else -> c.unknown }
            val chats = pick(byTurn[AdminQuery.turnReachThresholds.indexOf(1)])
            PlatformReach(name, chats, AdminQuery.turnReachThresholds.mapIndexed { i, turn -> val n = pick(byTurn[i]); ReachPoint(turn, n, ratio(n, chats)) })
        }
        return TurnReachResponse(clock.instant(), range.from, range.to, segment, platform.asOf, platform.pending, segments, GuestConversion(five, six, ratio(six, five)), platforms)
    }
    fun options(query: AdminSegmentRequest): ChatOptionResponse {
        val range = range(query); val segment = segment(query); val groups = repository.options(range, dashboard.excludedUserIds).groupBy { it.date() }
        val turns = repository.turnsByModel(range, dashboard.excludedUserIds).groupBy { it.date() }
        val models = repository.modelCodes(); val metrics = definitions.chatOptions(segment, models)
        val platform = platformValues("quality/chat-options", range, metrics)
        val starts = metrics.take(6).chunked(2); val modelTurns = models.zip(metrics.drop(6).chunked(3))
        return ChatOptionResponse(clock.instant(), range.from, range.to, segment, platform.asOf, platform.pending, range.dates().map { date ->
            val rows = groups[date].orEmpty()
            ChatOptionDay(date, rows.sumOf { it.n("chats") }, rows.sumOf { it.n("persona") },
                rows.associate { it["model"].toString() to it.n("chats") },
                turns[date].orEmpty().associate { it["model"].toString() to it.n("turns") },
                platform.day(date, starts), platform.day(date, starts.map { listOf(it[1]) }),
                if (date in platform.ready) modelTurns.associate { (code, group) -> code to platform.counts(group.map { listOf(it) }, listOf(date)) } else null)
        })
    }
}
