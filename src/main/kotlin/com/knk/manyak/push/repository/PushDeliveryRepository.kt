package com.knk.manyak.push.repository

import com.knk.manyak.push.dto.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Repository
class PushDeliveryRepository(private val jdbc: JdbcTemplate) {
    fun findCampaignId(publicId: UUID): Long? =
        jdbc.queryForList("SELECT id FROM push_campaigns WHERE public_id = ?", Long::class.java, publicId).singleOrNull()

    /** ON CONFLICT는 동시 보고도 직렬화하며 최초 행을 덮어쓰지 않는다. */
    fun insert(report: PushDeliveryReportRequest, campaignId: Long?, userId: Long?) {
        jdbc.update("""
            INSERT INTO push_delivery_results
                (message_id, type, kind, campaign_id, user_id, outcome, reason, delivered_devices, failed_devices, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
        """.trimIndent(), report.messageId, report.type.name, report.kind.name, campaignId, userId,
            report.outcome.name, report.reason, report.deliveredDevices, report.failedDevices, Timestamp.from(report.completedAt))
    }

    fun campaignStats(campaignId: Long): CampaignDeliveryStats = jdbc.queryForObject("""
        SELECT COUNT(CASE WHEN delivered_devices > 0 THEN 1 END) AS members,
               COALESCE(SUM(delivered_devices), 0) AS delivered, COALESCE(SUM(failed_devices), 0) AS failed
        FROM push_delivery_results WHERE campaign_id = ?
    """.trimIndent(), { rs, _ -> CampaignDeliveryStats(rs.getLong("members"), rs.getLong("delivered"), rs.getLong("failed")) }, campaignId)!!

    fun dailyStats(from: LocalDate, to: LocalDate, type: PushDeliveryType?, kind: PushKind? = null): List<PushDeliveryStatsItem> {
        val zone = ZoneId.of("Asia/Seoul")
        // KST는 DST가 없어 고정 오프셋 에포크 일수로 묶는다. AT TIME ZONE 뒤 DATE 캐스트는 DB 세션 시간대에 좌우된다(H2·UTC 세션 등).
        val offsetSeconds = zone.rules.getOffset(Instant.EPOCH).totalSeconds
        val args = mutableListOf<Any>(Timestamp.from(from.atStartOfDay(zone).toInstant()),
            Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()))
        if (type != null) args.add(type.name)
        if (kind != null) args.add(kind.name)
        return jdbc.query("""
            SELECT FLOOR((EXTRACT(EPOCH FROM completed_at) + $offsetSeconds) / 86400) AS epoch_day, type, kind,
                   COUNT(*) AS requested, COUNT(CASE WHEN delivered_devices > 0 THEN 1 END) AS members,
                   SUM(delivered_devices) AS delivered, SUM(failed_devices) AS failed
            FROM push_delivery_results WHERE completed_at >= ? AND completed_at < ?
            ${if (type != null) "AND type = ?" else ""}
            ${if (kind != null) "AND kind = ?" else ""}
            GROUP BY FLOOR((EXTRACT(EPOCH FROM completed_at) + $offsetSeconds) / 86400), type, kind
            ORDER BY epoch_day DESC, type ASC
        """.trimIndent(), { rs, _ -> PushDeliveryStatsItem(LocalDate.ofEpochDay(rs.getLong("epoch_day")),
            PushDeliveryType.valueOf(rs.getString("type")), PushKind.valueOf(rs.getString("kind")),
            rs.getLong("requested"), rs.getLong("members"), rs.getLong("delivered"), rs.getLong("failed")) }, *args.toTypedArray())
    }
}
