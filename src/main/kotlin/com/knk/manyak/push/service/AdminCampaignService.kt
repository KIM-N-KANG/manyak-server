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
import com.knk.manyak.push.repository.AdminCampaignListRepository
import com.knk.manyak.story.repository.StoryRepository
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
    private val campaignList: AdminCampaignListRepository,
    private val stories: StoryRepository,
    private val notices: com.knk.manyak.notice.service.NoticeService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(request: AdminCampaignListRequest): AdminCampaignPageResponse = campaignList.list(request)

    @Transactional(readOnly = true)
    fun detail(id: UUID): AdminCampaignResponse = response(find(id))

    @Transactional
    fun create(adminUserId: Long, request: AdminCampaignRequest): AdminCampaignResponse {
        val now = clock.instant()
        if (request.scheduledAt?.isBefore(now) == true) badRequest("예약 시각은 현재 이후여야 합니다.")
        if (request.storyId != null && request.noticeId != null) badRequest("이동 대상은 스토리와 공지 중 하나만 지정하세요.")
        val notice = request.noticeId?.let { notices.connectNotification(it) ?: badRequest("게시된 공지만 연결할 수 있습니다.") }
        val story = request.storyId?.let { publicId ->
            stories.findByPublicIdAndDeletedAtIsNull(publicId)?.takeIf { it.isPubliclyListed() }
                ?: badRequest("이동 대상은 공개 스토리여야 합니다.")
        }
        val row = campaigns.save(PushCampaign(title = request.title, body = request.body,
            scheduledAt = request.scheduledAt ?: now, createdAt = now, storyId = story?.id, noticeId = notice?.id,
            noticeTag = notice?.tag, kind = notice?.tag?.pushKind ?: PushKind.MARKETING))
        val response = response(row)
        audit.recordChange(adminUserId, AdminAuditAction.PUSH_CAMPAIGN_CREATED, row.publicId.toString(), request.reason,
            after = mapper.valueToTree(mapOf("kind" to row.kind, "title" to row.title, "body" to row.body,
                "scheduledAt" to row.scheduledAt, "status" to row.status, "storyId" to response.storyId, "noticeId" to response.noticeId)))
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
        val scheduledAt = find(id).scheduledAt
        if (campaigns.changeStatus(id, expected, next) == 0) throw ResponseStatusException(HttpStatus.CONFLICT, "변경할 수 없는 캠페인 상태입니다.")
        audit.recordChange(adminUserId, action, id.toString(), reason,
            mapper.valueToTree(mapOf("status" to expected, "scheduledAt" to scheduledAt)),
            mapper.valueToTree(mapOf("status" to next, "scheduledAt" to scheduledAt)))
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
        failureReason = row.failureReason, startedAt = row.startedAt, finishedAt = row.finishedAt, createdAt = row.createdAt,
        noticeId = row.noticeId?.let(campaigns::findNoticePublicId),
        storyId = row.storyId?.let { stories.findById(it).orElse(null)?.publicId },
        deliveredMembers = summary.deliveredMembers, deliveredDevices = summary.deliveredDevices, failedDevices = summary.failedDevices)
    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
