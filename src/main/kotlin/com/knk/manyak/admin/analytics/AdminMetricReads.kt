package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.dto.AdminDateRange
import com.knk.manyak.admin.repository.AdminQuery
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.time.*

@ConfigurationProperties("manyak.admin.ai-prices")
class AdminAiPrices(val models: Map<String, TokenPrice> = emptyMap(), val chatImageUnitCostKrw: BigDecimal = BigDecimal("48")) {
    init { require(chatImageUnitCostKrw.signum() >= 0) }
}
data class TokenPrice(val inputMillionKrw: BigDecimal, val outputMillionKrw: BigDecimal) {
    init { require(inputMillionKrw.signum() >= 0 && outputMillionKrw.signum() >= 0) }
}
data class SignupCounts(val total: Long, val google: Long, val kakao: Long, val other: Long)
data class SignupDay(val date: LocalDate, val signups: SignupCounts, val cumulativeSignups: Long, val migratedFromGuest: Long)
data class SignupResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val daily: List<SignupDay>)
data class ReachPoint(val turn: Int, val chats: Long, val rate: Double)
data class ReachSegment(val segment: String, val chats: Long, val reached: List<ReachPoint>)
data class GuestConversion(val chatsReached5: Long, val chatsReached6: Long, val rate: Double)
data class TurnReachResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val segments: List<ReachSegment>, val guestToMember: GuestConversion)
data class ChatOptionDay(val date: LocalDate, val chats: Long, val withPersona: Long, val byModel: Map<String, Long>, val turnsByModel: Map<String, Long>)
data class ChatOptionResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val daily: List<ChatOptionDay>)
data class AiCostItem(val feature: String, val model: String?, val calls: Long, val callsWithoutTokens: Long, val inputTokens: Long, val outputTokens: Long, val costKrw: BigDecimal?)
data class AiCostDay(val date: LocalDate, val byFeature: List<AiCostItem>)
data class AiCostTotals(val costKrw: BigDecimal, val costPerTurnKrw: BigDecimal?, val costPerStoryKrw: BigDecimal?)
data class AiCostResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val currency: String, val chatImageUnitCostKrw: BigDecimal, val daily: List<AiCostDay>, val totals: AiCostTotals, val unpricedModels: List<String>)

@Repository
class AdminMetricReadRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private fun day(column: String) = "CAST(FLOOR((EXTRACT(EPOCH FROM $column)+32400)/86400) AS BIGINT)"
    private fun params(range: AnalyticsRange, excluded: List<Long>) = mapOf("start" to range.from.atStartOfDay(AdminDateRange.KST).toOffsetDateTime(),
        "end" to range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toOffsetDateTime(), "excluded" to excluded)
    private fun exclude(column: String, excluded: List<Long>) = if (excluded.isEmpty()) "" else " AND ($column IS NULL OR $column NOT IN (:excluded))"
    fun signupsBefore(range: AnalyticsRange, excluded: List<Long>): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM users u WHERE u.created_at < :start ${exclude("u.id", excluded)}",
        params(range, excluded), Long::class.java) ?: 0L
    fun signups(range: AnalyticsRange, excluded: List<Long>): List<Map<String, Any?>> = jdbc.queryForList("""
        SELECT ${day("u.created_at")} AS metric_day, COUNT(*) AS total,
        SUM(CASE WHEN s.provider='GOOGLE' THEN 1 ELSE 0 END) AS google,
        SUM(CASE WHEN s.provider='KAKAO' THEN 1 ELSE 0 END) AS kakao,
        SUM(CASE WHEN u.migrated_at IS NOT NULL THEN 1 ELSE 0 END) AS migrated
        FROM users u LEFT JOIN (SELECT user_id, provider, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY connected_at, id) AS seq FROM social_accounts) s ON s.user_id=u.id AND s.seq=1
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
    // 판본은 별도 테이블이므로 활성 AI 응답 메시지만 센다. 채팅의 생성일과 삭제 여부는 제한하지 않는다.
    fun turnsByModel(range: AnalyticsRange, excluded: List<Long>): List<Map<String, Any?>> = jdbc.queryForList("""
        SELECT ${day("m.created_at")} AS metric_day, c.model_code AS model, COUNT(*) AS turns
        FROM story_messages m JOIN story_chats c ON c.id=m.chat_id
        WHERE m.created_at>=:start AND m.created_at<:end AND m.role='ASSISTANT' ${exclude("c.user_id", excluded)}
        GROUP BY ${day("m.created_at")}, c.model_code ORDER BY metric_day, model
        """, params(range, excluded))
    fun aiCost(range: AnalyticsRange): List<Map<String, Any?>> = jdbc.queryForList("""
        SELECT ${day("created_at")} AS metric_day, feature, model, COUNT(*) AS calls,
        SUM(CASE WHEN input_token_count IS NULL OR output_token_count IS NULL THEN 1 ELSE 0 END) AS missing,
        SUM(COALESCE(input_token_count,0)) AS input, SUM(COALESCE(output_token_count,0)) AS output
        FROM ai_call_logs WHERE created_at>=:start AND created_at<:end AND status='SUCCEEDED'
        AND feature IN ('chat_response','choice_generation','chat_image','storyline_generation','story_completion')
        GROUP BY ${day("created_at")}, feature, model ORDER BY metric_day, feature, model
        """, params(range, emptyList()))
}

@Service
@EnableConfigurationProperties(AdminAiPrices::class)
class AdminMetricReadService(private val repository: AdminMetricReadRepository, private val dashboard: AdminDashboardProperties,
    private val prices: AdminAiPrices, private val clock: Clock = Clock.systemUTC()) {
    private fun range(query: AdminChartsRequest) = query.resolve(clock).let { AnalyticsRange(it.from, it.to) }
    private fun Map<String, Any?>.n(key: String) = (this[key] as Number?)?.toLong() ?: 0L
    private fun Map<String, Any?>.date() = LocalDate.ofEpochDay(n("metric_day"))
    fun signups(query: AdminChartsRequest): SignupResponse {
        val range = range(query); val rows = repository.signups(range, dashboard.excludedUserIds).associateBy { it.date() }
        var cumulative = repository.signupsBefore(range, dashboard.excludedUserIds)
        return SignupResponse(clock.instant(), range.from, range.to, range.dates().map { date ->
            val row = rows[date] ?: emptyMap(); val total = row.n("total"); val google = row.n("google"); val kakao = row.n("kakao"); cumulative = Math.addExact(cumulative, total)
            SignupDay(date, SignupCounts(total, google, kakao, total-google-kakao), cumulative, row.n("migrated"))
        })
    }
    fun reach(query: AdminChartsRequest): TurnReachResponse {
        val range = range(query); val rows = repository.reach(range, dashboard.excludedUserIds)
        fun ratio(n: Long, d: Long) = if (d == 0L) 0.0 else n.toDouble()/d
        val segments = listOf("all", "guest", "member").map { segment ->
            val group = rows.filter { segment == "all" || it["segment"] == segment }; val count = group.sumOf { it.n("chats") }
            ReachSegment(segment, count, AdminQuery.turnReachThresholds.map { turn -> val n = group.sumOf { it.n("turn_$turn") }; ReachPoint(turn, n, ratio(n, count)) })
        }
        val five = rows.sumOf { it.n("migrated_5") }; val six = rows.sumOf { it.n("migrated_6") }
        return TurnReachResponse(clock.instant(), range.from, range.to, segments, GuestConversion(five, six, ratio(six, five)))
    }
    fun options(query: AdminChartsRequest): ChatOptionResponse {
        val range = range(query); val groups = repository.options(range, dashboard.excludedUserIds).groupBy { it.date() }
        val turns = repository.turnsByModel(range, dashboard.excludedUserIds).groupBy { it.date() }
        return ChatOptionResponse(clock.instant(), range.from, range.to, range.dates().map { date ->
            val rows = groups[date].orEmpty()
            ChatOptionDay(date, rows.sumOf { it.n("chats") }, rows.sumOf { it.n("persona") },
                rows.associate { it["model"].toString() to it.n("chats") },
                turns[date].orEmpty().associate { it["model"].toString() to it.n("turns") })
        })
    }
    fun aiCost(query: AdminChartsRequest): AiCostResponse {
        val range = range(query); val rows = repository.aiCost(range).groupBy { it.date() }
        val daily = range.dates().map { date -> AiCostDay(date, rows[date].orEmpty().map { row ->
            val model = row["model"] as String?; val image = row["feature"] == "chat_image"
            val input = if (image) 0L else row.n("input"); val output = if (image) 0L else row.n("output")
            val cost = if (image) prices.chatImageUnitCostKrw * BigDecimal.valueOf(row.n("calls")) else prices.models[model]?.let { (it.inputMillionKrw * BigDecimal.valueOf(input) + it.outputMillionKrw * BigDecimal.valueOf(output)).divide(BigDecimal.valueOf(1_000_000)) }
            AiCostItem(row["feature"].toString(), model, row.n("calls"), if (image) 0L else row.n("missing"), input, output, cost)
        }) }
        val all = daily.flatMap { it.byFeature }
        fun cost(items: List<AiCostItem>) = items.fold(BigDecimal.ZERO) { acc, item -> acc + (item.costKrw ?: BigDecimal.ZERO) }
        fun per(features: Set<String>, denominator: String): BigDecimal? {
            val calls = all.filter { it.feature == denominator }.sumOf { it.calls }
            return if (calls == 0L) null else cost(all.filter { it.feature in features }).divide(BigDecimal.valueOf(calls), MathContext.DECIMAL128)
        }
        return AiCostResponse(clock.instant(), range.from, range.to, "KRW", prices.chatImageUnitCostKrw, daily,
            AiCostTotals(cost(all), per(setOf("chat_response", "choice_generation"), "chat_response"), per(setOf("storyline_generation", "story_completion"), "story_completion")),
            all.filter { it.costKrw == null }.mapNotNull { it.model }.distinct().sorted())
    }
}
