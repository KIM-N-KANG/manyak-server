package com.knk.manyak.push.service

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.dto.*
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.repository.CampaignDeliverySummaryRepository
import com.knk.manyak.push.repository.CampaignDeliverySummary
import com.knk.manyak.push.repository.PushCampaignRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

@Service
class AdminCampaignService(
    private val campaigns: PushCampaignRepository,
    private val users: UserRepository,
    private val audit: AdminAuditService,
    private val mapper: ObjectMapper,
    private val deliveries: CampaignDeliverySummaryRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(status: PushCampaignStatus?, page: Int, size: Int, sort: String, order: String): AdminCampaignPageResponse {
        val fields = mapOf("campaignId" to "publicId", "createdAt" to "createdAt", "scheduledAt" to "scheduledAt",
            "status" to "status", "kind" to "kind", "title" to "title", "targetCount" to "targetCount",
            "sentCount" to "sentCount", "startedAt" to "startedAt", "finishedAt" to "finishedAt")
        val field = fields[sort] ?: badRequest("지원하지 않는 정렬입니다.")
        val direction = when (order.lowercase()) {
            "asc" -> Sort.Direction.ASC
            "desc" -> Sort.Direction.DESC
            else -> badRequest("정렬 방향은 asc 또는 desc여야 합니다.")
        }
        if (page < 1 || size !in 1..100) badRequest("page는 1 이상, size는 1~100이어야 합니다.")
        val ordering = Sort.by(direction, field).let { if (field == "publicId") it else it.and(Sort.by("publicId")) }
        val predicate = Specification<PushCampaign> { root, _, cb -> status?.let { cb.equal(root.get<PushCampaignStatus>("status"), it) } ?: cb.conjunction() }
        val result = campaigns.findAll(predicate, PageRequest.of(page - 1, size, ordering))
        val summaries = deliveries.summaries(result.content.map { it.id })
        return AdminCampaignPageResponse(result.content.map { response(it, summaries[it.id] ?: CampaignDeliverySummary()) }, page, size, result.totalElements, result.totalPages)
    }

    @Transactional(readOnly = true)
    fun detail(id: UUID): AdminCampaignResponse = response(find(id))

    @Transactional
    fun create(adminUserId: Long, request: AdminCampaignRequest): AdminCampaignResponse {
        val now = clock.instant()
        if (request.scheduledAt?.isBefore(now) == true) badRequest("예약 시각은 현재 이후여야 합니다.")
        val row = campaigns.save(PushCampaign(title = request.title, body = request.body,
            scheduledAt = request.scheduledAt ?: now, createdAt = now))
        val response = response(row)
        audit.recordChange(adminUserId, AdminAuditAction.PUSH_CAMPAIGN_CREATED, row.publicId.toString(), request.reason,
            after = mapper.valueToTree(response))
        return response
    }

    @Transactional
    fun cancel(adminUserId: Long, id: UUID, reason: String) = transition(adminUserId, id, reason,
        PushCampaignStatus.SCHEDULED, PushCampaignStatus.CANCELED, AdminAuditAction.PUSH_CAMPAIGN_CANCELED)

    @Transactional
    fun retry(adminUserId: Long, id: UUID, reason: String) = transition(adminUserId, id, reason,
        PushCampaignStatus.FAILED, PushCampaignStatus.SCHEDULED, AdminAuditAction.PUSH_CAMPAIGN_RETRIED)

    private fun transition(adminUserId: Long, id: UUID, reason: String, expected: PushCampaignStatus,
        next: PushCampaignStatus, action: AdminAuditAction): AdminCampaignResponse {
        find(id)
        if (campaigns.changeStatus(id, expected, next) == 0) throw ResponseStatusException(HttpStatus.CONFLICT, "변경할 수 없는 캠페인 상태입니다.")
        audit.recordChange(adminUserId, action, id.toString(), reason,
            mapper.createObjectNode().put("status", expected.name), mapper.createObjectNode().put("status", next.name))
        return response(find(id))
    }

    @Transactional(readOnly = true)
    fun targetCount(kind: PushKind): AdminCampaignTargetResponse {
        val now = clock.instant()
        val hour = now.atZone(ZoneId.of("Asia/Seoul")).hour
        return AdminCampaignTargetResponse(kind, users.countPushTargets(kind == PushKind.MARKETING, false),
            if (kind == PushKind.MARKETING) users.countPushTargets(true, true) else null, hour >= 21 || hour < 8, now)
    }

    private fun find(id: UUID) = campaigns.findByPublicId(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "캠페인이 없습니다.")
    private fun response(row: PushCampaign, summary: CampaignDeliverySummary = deliveries.summaries(listOf(row.id))[row.id] ?: CampaignDeliverySummary()) = AdminCampaignResponse(row.publicId, row.kind, row.title, row.body,
        row.scheduledAt, row.status, targetCount = row.targetCount, sentCount = row.sentCount,
        startedAt = row.startedAt, finishedAt = row.finishedAt, createdAt = row.createdAt,
        deliveredMembers = summary.deliveredMembers, deliveredDevices = summary.deliveredDevices, failedDevices = summary.failedDevices)
    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
