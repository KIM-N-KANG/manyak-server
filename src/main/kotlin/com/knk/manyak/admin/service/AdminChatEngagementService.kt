package com.knk.manyak.admin.service

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.AdminChatEngagementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Service
class AdminChatEngagementService(
    private val repository: AdminChatEngagementRepository,
    private val properties: AdminDashboardProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun engagement(request: AdminChartsRequest): AdminChatEngagementResponse {
        val now = clock.instant()
        val range = request.resolve(Clock.fixed(now, clock.zone))
        val start = range.from.atStartOfDay(AdminDateRange.KST).toInstant()
        val end = minOf(now, range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant().minusNanos(1000))
        val excluded = properties.excludedUserIds
        val activity = repository.activity(range.from.minusDays(30).atStartOfDay(AdminDateRange.KST).toInstant(), end, excluded)
        val pairsByDate = activity.groupBy { it.date }.mapValues { (_, rows) -> rows.map { it.userId to it.chatId }.toSet() }
        val membersByDate = pairsByDate.mapValues { (_, pairs) -> pairs.map { it.first }.toSet() }
        val turns = repository.turns(start, end, excluded)
        val regenerations = repository.regenerations(start, end, excluded)
        val periodMembers = mutableSetOf<Long>()
        // 날짜별 고유 회원의 출현 일수를 관리해 날짜가 늘어나도 30일 창을 다시 합치지 않는다.
        val monthlyFrequency = mutableMapOf<Long, Int>()
        fun change(date: LocalDate, delta: Int) {
            membersByDate[date].orEmpty().forEach { member ->
                val count = monthlyFrequency.getOrDefault(member, 0) + delta
                if (count == 0) monthlyFrequency.remove(member) else monthlyFrequency[member] = count
            }
        }
        for (offset in 29L downTo 1L) change(range.from.minusDays(offset), 1)
        var monthlyActive = 0L
        fun ratio(numerator: Long, denominator: Long) = if (denominator == 0L) null else numerator.toDouble() / denominator
        val days = ChronoUnit.DAYS.between(range.from, range.to).toInt() + 1
        val daily = (0 until days).map { offset ->
            val date = range.from.plusDays(offset.toLong())
            val members = membersByDate[date].orEmpty()
            periodMembers.addAll(members)
            change(date, 1)
            monthlyActive = monthlyFrequency.size.toLong()
            val previousPairs = pairsByDate[date.minusDays(1)].orEmpty()
            val continued = pairsByDate[date].orEmpty().filter { it in previousPairs }.map { it.first }.toSet().size.toLong()
            val active = members.size.toLong()
            val dayTurns = turns[date] ?: 0L
            val dayRegenerations = regenerations[date] ?: 0L
            val result = AdminChatEngagementDay(date, active, dayTurns, ratio(dayTurns, active), continued,
                ratio(continued, membersByDate[date.minusDays(1)].orEmpty().size.toLong()), dayRegenerations,
                ratio(dayRegenerations, dayTurns), ratio(active, monthlyActive))
            change(date.minusDays(29), -1)
            result
        }
        val active = periodMembers.size.toLong()
        return AdminChatEngagementResponse(now, range.from, range.to, active, monthlyActive,
            ratio(daily.sumOf { it.turns }, active), daily)
    }
}
