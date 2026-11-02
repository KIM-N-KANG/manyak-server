package com.knk.manyak.admin.service

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.AdminChartsRepository
import com.knk.manyak.story.service.OfficialStoryAccount
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock

@Service
class AdminChartsService(
    private val repository: AdminChartsRepository,
    private val properties: AdminDashboardProperties,
    private val official: OfficialStoryAccount,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun charts(days: Int): AdminChartsResponse = charts(AdminChartsRequest().apply { this.days = days.toString() })

    fun charts(request: AdminChartsRequest): AdminChartsResponse {
        val range = request.resolve(clock)
        val now = clock.instant()
        val from = range.from
        val to = range.to
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1
        val start = from.atStartOfDay(AdminDateRange.KST).toInstant()
        // DB timestamp 정밀도(마이크로초)에 맞춰 다음 KST 자정 직전까지만 포함한다.
        val end = minOf(now, to.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant().minusNanos(1000))
        val daily = repository.daily(start, end, properties.excludedUserIds)
        val previousFrom = from.minusDays(days.toLong())
        val previousTo = from.minusDays(1)
        val previousDaily = repository.daily(previousFrom.atStartOfDay(AdminDateRange.KST).toInstant(),
            start.minusNanos(1000), properties.excludedUserIds)
        fun totals(rows: Collection<Map<String, Long>>): AdminChartTotals {
            fun sum(key: String) = rows.sumOf { it[key] ?: 0L }
            return AdminChartTotals(sum("joined"), sum("chats"), sum("turns"), sum("stories"),
                sum("granted"), sum("spent"), sum("payments"), sum("revenue"))
        }
        val raw = repository.distributions(start, end, properties.excludedUserIds, official.officialUserId())
        fun Map<String, Any?>.number(key: String) = (this[key] as? Number)?.toDouble() ?: 0.0
        fun rounded(value: Double) = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()
        fun point(label: String, value: Double, list: String, params: Map<String, String>, prefix: String = "") =
            AdminChartPoint(label, value, AdminChartLink(list, params + mapOf("${prefix}From".replaceFirstChar { it.lowercase() } to from.toString(),
                "${prefix}To".replaceFirstChar { it.lowercase() } to to.toString())))
        fun buckets(index: Int, labels: List<String>, list: String, key: String, prefix: String = "") = labels.mapIndexed { i, label ->
            point(label, raw[index].firstOrNull { it.number("bucket").toInt() == i }?.number("n") ?: 0.0, list, mapOf(key to i.toString()), prefix)
        }
        // 다중 장르 문자열 그룹을 분리하되 채팅 수로 가중 평균한다. 조합 문자열을 개별 행으로 메모리에 적재하지 않는다.
        val genres = mutableMapOf<String, Pair<Long, Long>>()
        raw[0].forEach { row ->
            (row["label"] as? String)?.split(", ")?.filter { it.isNotBlank() }?.distinct()?.forEach { genre ->
                val old = genres[genre] ?: (0L to 0L)
                genres[genre] = old.first + (row["turns"] as Number).toLong() to old.second + (row["n"] as Number).toLong()
            }
        }
        val total = raw[2].sumOf { it.number("n") }
        val aiFeatures = listOf("chat_response", "choice_generation", "chat_image", "storyline_generation", "story_completion", "chat_compact")
        fun ai(index: Int, status: String) = aiFeatures.map { feature ->
            val params = mutableMapOf("aiFeature" to feature, "aiStatus" to status)
            if (index == 5) params["hasLatency"] = "true"
            val value = raw[index].firstOrNull { it["label"] == feature }?.number("metric_value") ?: 0.0
            point(feature, if (index == 6) rounded(value) else value,
                if (feature in setOf("chat_response", "choice_generation", "chat_image", "chat_compact")) "chats" else "stories", params, "ai")
        }
        val wait = raw[4].single()
        val charts = listOf(
            AdminDistribution("genre-depth", "장르별 평균 턴 수", "채팅 생성일 기준 장르별 평균 현재 턴 수", "턴",
                genres.map { (genre, value) ->
                    point(genre, value.first.toDouble() / value.second, "stories", mapOf("genre" to genre), "chat")
                        .copy(chats = value.second, totalTurns = value.first)
                }.sortedWith(compareByDescending<AdminChartPoint> { it.value }.thenBy { it.label })),
            AdminDistribution("turns", "턴 수 분포", "채팅 생성일을 기준으로 구간별 채팅 수 집계", "개",
                buckets(1, listOf("0", "1", "2", "3", "4", "5", "6", "7~9", "10~14", "15~19", "20~29", "30~49", "50+"), "chats", "turnBucket")),
            AdminDistribution("started-as", "채팅 소유자 게스트와 회원", "채팅 생성일 기준 채팅 수로 나눈 비율, 가입 뒤 이관된 게스트 채팅은 회원, 게스트 사용자 수는 AI 호출을 보낸 기기 수", "%",
                listOf("GUEST" to "게스트", "MEMBER" to "회원").map { (key, label) ->
                    val row = raw[2].firstOrNull { it["label"] == key } ?: emptyMap()
                    point(label, rounded(if (total == 0.0) 0.0 else 100 * row.number("n") / total), "chats", mapOf("startedAs" to key))
                        .copy(chats = row.number("n").toLong(), users = row.number("users").toLong()) }),
            AdminDistribution("member-chats", "회원별 채팅 생성 횟수", "기간에 채팅을 생성한 회원의 생성 횟수 분포", "명",
                buckets(3, listOf("1개", "2개", "3~4개", "5~9개", "10개+"), "members", "chatBucket", "chat")),
            AdminDistribution("first-message", "첫 사용자 입력까지 걸린 시간", "채팅 생성일 기준, 900초 초과 ${wait.number("overdue").toLong()}건, 입력 없음 ${wait.number("missing").toLong()}건 제외", "초",
                listOf("average" to "평균", "p50" to "P50", "p75" to "P75", "p90" to "P90").map { (key, label) -> point(label, wait.number(key), "chats", mapOf("hasFirstMessage" to "true")) }),
            AdminDistribution("ai-latency", "기능별 AI 평균 호출 시간", "호출 시간이 기록된 성공 호출의 평균 호출 시간", "초", ai(5, "SUCCEEDED")),
            AdminDistribution("ai-success", "기능별 AI 성공률", "완료된 호출 중 성공 비율, 진행 중 제외", "%", ai(6, "FINISHED")),
            AdminDistribution("creation-mode", "제작 방식별 스토리 수", "스토리 생성일 기준 미삭제 스토리 수, 간편 제작은 스토리를 완성한 제작 세션이 있는 스토리", "개",
                listOf("GENERAL" to "일반 제작", "SIMPLE" to "간편 제작").map { (key, label) ->
                    point(label, raw[8].firstOrNull { it["label"] == key }?.number("n") ?: 0.0, "stories", mapOf("creationMode" to key)) }),
            AdminDistribution("characters", "간편 제작별 입력 주변 인물 수", "미삭제 스토리의 최신 완료 간편 제작 생성일 기준 직접 입력한 주변 인물 수, 공식 계정 포함", "건",
                buckets(7, (0..5).map { "${it}명" }, "stories", "supportingCharacterCount", "creation")),
        )
        return AdminChartsResponse(now, from, to, (0 until days).map { offset ->
            val date = from.plusDays(offset.toLong()); val values = daily[date] ?: emptyMap()
            AdminDailyMetrics(date, values["joined"] ?: 0, values["chats"] ?: 0, values["turns"] ?: 0, values["stories"] ?: 0,
                values["granted"] ?: 0, values["spent"] ?: 0, values["payments"] ?: 0, values["revenue"] ?: 0, values["attendance"] ?: 0)
        }, charts, totals(daily.values), AdminChartPreviousPeriod(previousFrom, previousTo, totals(previousDaily.values)))
    }
}
