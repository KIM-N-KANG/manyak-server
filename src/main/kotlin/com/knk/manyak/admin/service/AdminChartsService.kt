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
        val genres = mutableMapOf<String, Pair<Double, Double>>()
        raw[0].forEach { row ->
            (row["label"] as? String)?.split(", ")?.filter { it.isNotBlank() }?.distinct()?.forEach { genre ->
                val old = genres[genre] ?: (0.0 to 0.0)
                genres[genre] = old.first + row.number("turns") to old.second + row.number("n")
            }
        }
        val total = raw[2].sumOf { it.number("n") }
        val aiFeatures = listOf("chat_response", "choice_generation", "storyline_generation", "story_completion")
        fun ai(index: Int, status: String) = aiFeatures.map { feature ->
            val params = mutableMapOf("aiFeature" to feature, "aiStatus" to status)
            if (index == 5) params["hasLatency"] = "true"
            val value = raw[index].firstOrNull { it["label"] == feature }?.number("metric_value") ?: 0.0
            point(feature, if (index == 6) rounded(value) else value,
                if (feature in aiFeatures.take(2)) "chats" else "stories", params, "ai")
        }
        val wait = raw[4].single()
        val charts = listOf(
            AdminDistribution("genre-depth", "장르별 평균 턴 수", "채팅 생성일 기준 장르별 평균 현재 턴 수", "턴",
                genres.toSortedMap().map { (genre, value) -> point(genre, value.first / value.second, "stories", mapOf("genre" to genre), "chat") }),
            AdminDistribution("turns", "턴 수 분포", "채팅 생성일을 기준으로 구간별 채팅 수 집계", "개",
                buckets(1, listOf("0", "1", "2", "3~4", "5~6", "7~9", "10~14", "15~19", "20~29", "30~49", "50+"), "chats", "turnBucket")),
            AdminDistribution("started-as", "게스트와 회원 비율", "현재 소유 회원 여부로 판정한 채팅 비율", "%",
                listOf("GUEST" to "게스트", "MEMBER" to "회원").map { (key, label) -> point(label,
                    rounded(if (total == 0.0) 0.0 else 100 * (raw[2].firstOrNull { it["label"] == key }?.number("n") ?: 0.0) / total),
                    "chats", mapOf("startedAs" to key)) }),
            AdminDistribution("member-chats", "회원별 채팅 생성 횟수", "기간에 채팅을 생성한 회원의 생성 횟수 분포", "명",
                buckets(3, listOf("1개", "2개", "3~4개", "5~9개", "10개+"), "members", "chatBucket", "chat")),
            AdminDistribution("first-message", "첫 사용자 입력까지 걸린 시간", "채팅 생성일 기준, 900초 초과 ${wait.number("overdue").toLong()}건, 입력 없음 ${wait.number("missing").toLong()}건 제외", "초",
                listOf("average" to "평균", "p50" to "P50", "p75" to "P75", "p90" to "P90").map { (key, label) -> point(label, wait.number(key), "chats", mapOf("hasFirstMessage" to "true")) }),
            AdminDistribution("ai-latency", "기능별 AI 평균 호출 시간", "호출 시간이 기록된 성공 호출의 평균 호출 시간", "초", ai(5, "SUCCEEDED")),
            AdminDistribution("ai-success", "기능별 AI 성공률", "완료된 호출 중 성공 비율, 진행 중 제외", "%", ai(6, "FINISHED")),
            AdminDistribution("characters", "간편 제작별 입력 주변 인물 수", "미삭제 스토리의 최신 완료 간편 제작 생성일 기준 직접 입력한 주변 인물 수, 공식 계정 포함", "건",
                buckets(7, (0..5).map { "${it}명" }, "stories", "supportingCharacterCount", "creation")),
        )
        return AdminChartsResponse(now, from, to, (0 until days).map { offset ->
            val date = from.plusDays(offset.toLong()); val values = daily[date] ?: emptyMap()
            AdminDailyMetrics(date, values["joined"] ?: 0, values["chats"] ?: 0, values["turns"] ?: 0, values["stories"] ?: 0,
                values["granted"] ?: 0, values["spent"] ?: 0, values["payments"] ?: 0, values["revenue"] ?: 0, values["attendance"] ?: 0)
        }, charts)
    }
}
