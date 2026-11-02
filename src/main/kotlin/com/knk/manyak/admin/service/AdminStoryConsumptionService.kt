package com.knk.manyak.admin.service

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.AdminStoryConsumptionRepository
import com.knk.manyak.story.service.OfficialStoryAccount
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class AdminStoryConsumptionService(
    private val repository: AdminStoryConsumptionRepository,
    private val properties: AdminDashboardProperties,
    private val official: OfficialStoryAccount,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun consumption(request: AdminStoryConsumptionRequest): AdminStoryConsumptionResponse {
        val now = clock.instant()
        val range = request.resolve(Clock.fixed(now, clock.zone))
        val start = range.from.atStartOfDay(AdminDateRange.KST).toInstant()
        val end = minOf(now, range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant().minusNanos(1000))
        // KNK-1965 차트와 같은 직전 기간: 현재 범위와 같은 날짜 수이며 시작일 바로 전날까지다.
        val days = ChronoUnit.DAYS.between(range.from, range.to) + 1
        val previousStart = range.from.minusDays(days).atStartOfDay(AdminDateRange.KST).toInstant()
        val excluded = properties.excludedUserIds
        val officialId = official.officialUserId()
        val counts = repository.counts(start, end, excluded, officialId)
        return AdminStoryConsumptionResponse(now, range.from, range.to, counts.publicStories, counts.officialStories,
            counts.playedStories, if (counts.publicStories == 0L) null else counts.playedStories.toDouble() / counts.publicStories,
            counts.otherMemberChats, if (counts.otherMemberChats == 0L) null else counts.topChats.toDouble() / counts.otherMemberChats,
            repository.ranking(start, end, excluded, officialId, request.limit), repository.creators(previousStart, start, end, excluded))
    }
}
