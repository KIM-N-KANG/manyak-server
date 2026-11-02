package com.knk.manyak.push.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class CampaignDeliverySummary(val deliveredMembers: Long = 0, val deliveredDevices: Long = 0, val failedDevices: Long = 0)

/** 발송 결과 기록 도메인의 테이블을 읽기만 한다. 캠페인 관리에서는 결과 행을 생성하거나 변경하지 않는다. */
@Repository
class CampaignDeliverySummaryRepository(private val jdbc: JdbcTemplate) {
    fun summaries(campaignIds: List<Long>): Map<Long, CampaignDeliverySummary> {
        if (campaignIds.isEmpty()) return emptyMap()
        // S1 기반의 독립 캠페인 릴리스에는 결과 보고 테이블이 아직 없다. 결과 기록 도메인이 설치되면 같은 집계를 사용한다.
        val installed = jdbc.queryForObject("""SELECT COUNT(*) FROM information_schema.tables
            WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = 'push_delivery_results'""", Long::class.java) ?: 0
        if (installed == 0L) return emptyMap()
        return NamedParameterJdbcTemplate(jdbc).query("""SELECT r.campaign_id,
            COUNT(DISTINCT CASE WHEN ${campaignMemberDeliveryCondition()} THEN r.user_id END) AS delivered_members,
            SUM(r.delivered_devices) AS delivered_devices, SUM(r.failed_devices) AS failed_devices
            FROM push_delivery_results r JOIN push_campaigns c ON c.id=r.campaign_id
            WHERE r.campaign_id IN (:ids) GROUP BY r.campaign_id""",
            mapOf("ids" to campaignIds)) { rs, _ -> rs.getLong("campaign_id") to CampaignDeliverySummary(
                rs.getLong("delivered_members"), rs.getLong("delivered_devices"), rs.getLong("failed_devices")) }.toMap()
    }
}
