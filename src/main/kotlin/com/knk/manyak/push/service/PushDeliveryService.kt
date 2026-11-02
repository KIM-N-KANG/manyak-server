package com.knk.manyak.push.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.dto.*
import com.knk.manyak.push.repository.PushDeliveryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class PushDeliveryService(
    private val repository: PushDeliveryRepository,
    private val users: UserRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    // AFTER_COMMIT 리스너에서도 완료된 원래 트랜잭션과 분리해 실제로 커밋한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(report: PushDeliveryReportRequest) {
        if ((report.outcome == PushDeliveryOutcome.DELIVERED) != (report.deliveredDevices > 0)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "도달 결과와 도달 기기 수가 일치하지 않습니다.")
        }
        val campaignId = report.campaignId?.let { publicId ->
            repository.findCampaignId(publicId) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "없는 캠페인입니다.")
        }
        repository.insert(report, campaignId, users.findByPublicId(report.recipientId)?.id)
    }

    @Transactional(readOnly = true)
    fun campaignReach(from: LocalDate, to: LocalDate): List<PushCampaignReach> = repository.campaignReach(from, to)

    @Transactional(readOnly = true)
    fun campaignReach(id: java.util.UUID): PushCampaignReach? = repository.campaignReach(id)

    @Transactional(readOnly = true)
    fun activityReach(from: LocalDate, to: LocalDate, types: List<PushDeliveryType>): List<PushActivityReach> = repository.activityReach(from, to, types)

    @Transactional(readOnly = true)
    fun reachedMemberDeliveries(campaignId: java.util.UUID): List<PushMemberDelivery> = repository.reachedMemberDeliveries(campaignId)

    @Transactional(readOnly = true)
    fun reachedMemberDeliveries(from: LocalDate, to: LocalDate, types: List<PushDeliveryType>): List<PushMemberDelivery> =
        repository.reachedMemberDeliveries(from, to, types)

    /** S8 캠페인 상세에서 이미 해석한 내부 PK로 호출한다. 결과가 없어도 모두 0이다. */
    @Transactional(readOnly = true)
    fun campaignStats(campaignId: Long): CampaignDeliveryStats = repository.campaignStats(campaignId)

    @Transactional(readOnly = true)
    fun stats(from: LocalDate?, to: LocalDate?, type: PushDeliveryType?, kind: PushKind? = null): PushDeliveryStatsResponse {
        val end = to ?: LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
        val start = from ?: end.minusDays(6)
        val days = ChronoUnit.DAYS.between(start, end) + 1
        if (days !in 1..366) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "통계 기간은 1~366일이어야 합니다.")
        return PushDeliveryStatsResponse(start, end, repository.dailyStats(start, end, type, kind))
    }
}
