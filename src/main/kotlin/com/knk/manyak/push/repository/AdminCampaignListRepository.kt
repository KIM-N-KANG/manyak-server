package com.knk.manyak.push.repository

import com.knk.manyak.admin.repository.AdminQuery
import com.knk.manyak.admin.repository.adminPage
import com.knk.manyak.admin.repository.instant
import com.knk.manyak.admin.repository.longOrNull
import com.knk.manyak.push.dto.AdminCampaignListRequest
import com.knk.manyak.push.dto.AdminCampaignPageResponse
import com.knk.manyak.push.dto.AdminCampaignResponse
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.entity.PushCampaignStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/** 도달 수는 전체 결과 행에서 집계한 뒤 정렬·페이지 분할한다. 결과가 없는 캠페인도 0으로 포함한다. */
@Repository
class AdminCampaignListRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun list(request: AdminCampaignListRequest): AdminCampaignPageResponse {
        val page = request.pagination(mapOf(
            "createdAt" to "c.created_at", "scheduledAt" to "c.scheduled_at", "title" to "c.title",
            "kind" to "c.kind", "status" to "c.status", "targetCount" to "c.target_count", "sentCount" to "c.sent_count",
            "deliveredMembers" to "COALESCE(d.delivered_members, 0)",
            "deliveredDevices" to "COALESCE(d.delivered_devices, 0)", "failedDevices" to "COALESCE(d.failed_devices, 0)",
        ), "createdAt")
        val query = AdminQuery().apply {
            equal("c.status", request.status?.name)
            equal("c.kind", request.kind?.name)
            request.normalizedQuery()?.let { search ->
                params["search"] = search
                val matches = mutableListOf("POSITION(:search IN ${AdminQuery.normalized("c.title")}) > 0",
                    "POSITION(:search IN ${AdminQuery.normalized("c.body")}) > 0")
                runCatching { UUID.fromString(search) }.getOrNull()?.takeIf { it.toString() == search }?.let {
                    params["searchId"] = it
                    matches += "c.public_id = :searchId"
                }
                add(matches.joinToString(" OR ", "(", ")"))
            }
        }
        val result = jdbc.adminPage("""c.*, n.public_id AS notice_public_id, s.public_id AS story_public_id,
            COALESCE(d.delivered_members, 0) AS delivered_members,
            COALESCE(d.delivered_devices, 0) AS delivered_devices, COALESCE(d.failed_devices, 0) AS failed_devices""",
            """FROM push_campaigns c
            LEFT JOIN notices n ON n.id=c.notice_id
            LEFT JOIN stories s ON s.id=c.story_id
            LEFT JOIN (
                SELECT campaign_id, SUM(CASE WHEN delivered_devices > 0 THEN 1 ELSE 0 END) AS delivered_members,
                    SUM(delivered_devices) AS delivered_devices, SUM(failed_devices) AS failed_devices
                FROM push_delivery_results GROUP BY campaign_id
            ) d ON d.campaign_id=c.id""", query, page, "c.public_id", RowMapper { rs, _ ->
                AdminCampaignResponse(
                    campaignId = rs.getObject("public_id", UUID::class.java), kind = PushKind.valueOf(rs.getString("kind")),
                    title = rs.getString("title"), body = rs.getString("body"), scheduledAt = rs.instant("scheduled_at")!!,
                    status = PushCampaignStatus.valueOf(rs.getString("status")), failureReason = rs.getString("failure_reason"),
                    noticeId = rs.getObject("notice_public_id", UUID::class.java), storyId = rs.getObject("story_public_id", UUID::class.java),
                    targetCount = rs.longOrNull("target_count")?.toInt(), sentCount = rs.longOrNull("sent_count")?.toInt(),
                    startedAt = rs.instant("started_at"), finishedAt = rs.instant("finished_at"), createdAt = rs.instant("created_at")!!,
                    deliveredMembers = rs.getLong("delivered_members"), deliveredDevices = rs.getLong("delivered_devices"),
                    failedDevices = rs.getLong("failed_devices"),
                )
            })
        return AdminCampaignPageResponse(result.items, result.page, result.size, result.totalElements, result.totalPages.toInt())
    }
}
