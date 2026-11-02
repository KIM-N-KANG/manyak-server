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
    fun campaignReach(from: LocalDate, to: LocalDate): List<PushCampaignReach> {
        val zone = ZoneId.of("Asia/Seoul")
        return jdbc.query("""SELECT c.public_id,c.kind,c.title,n.public_id AS notice_id,c.started_at,c.deep_link_included,
            (SELECT COUNT(*) FROM push_delivery_results r WHERE r.campaign_id=c.id AND r.delivered_devices>0) AS reached
            FROM push_campaigns c LEFT JOIN notices n ON n.id=c.notice_id
            WHERE c.started_at >= ? AND c.started_at < ? ORDER BY c.started_at DESC,c.public_id ASC""",
            { rs, _ -> PushCampaignReach(rs.getObject("public_id",UUID::class.java),PushKind.valueOf(rs.getString("kind")),rs.getString("title"),
                rs.getObject("notice_id",UUID::class.java),rs.getTimestamp("started_at").toInstant(),rs.getObject("deep_link_included") as Boolean?,rs.getLong("reached")) },
            Timestamp.from(from.atStartOfDay(zone).toInstant()),Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()))
    }

    fun campaignReach(publicId: UUID): PushCampaignReach? = jdbc.query("""SELECT c.public_id,c.kind,c.title,n.public_id AS notice_id,c.started_at,c.deep_link_included,
        CASE WHEN c.started_at IS NULL THEN 0 ELSE (SELECT COUNT(*) FROM push_delivery_results r WHERE r.campaign_id=c.id AND r.delivered_devices>0) END AS reached
        FROM push_campaigns c LEFT JOIN notices n ON n.id=c.notice_id WHERE c.public_id=?""",
        { rs, _ -> PushCampaignReach(rs.getObject("public_id",UUID::class.java),PushKind.valueOf(rs.getString("kind")),rs.getString("title"),
            rs.getObject("notice_id",UUID::class.java),rs.getTimestamp("started_at")?.toInstant(),rs.getObject("deep_link_included") as Boolean?,rs.getLong("reached")) }, publicId).singleOrNull()

    fun activityReach(from: LocalDate, to: LocalDate, types: List<PushDeliveryType>): List<PushActivityReach> {
        if (types.isEmpty()) return emptyList()
        val zone = ZoneId.of("Asia/Seoul")
        return org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
            SELECT type,FLOOR((EXTRACT(EPOCH FROM completed_at)+32400)/86400) AS epoch_day,COUNT(DISTINCT user_id) AS reached
            FROM push_delivery_results WHERE completed_at>=:from AND completed_at<:to
                AND type IN (:types) AND delivered_devices>0 AND user_id IS NOT NULL
            GROUP BY type,FLOOR((EXTRACT(EPOCH FROM completed_at)+32400)/86400) ORDER BY epoch_day ASC,type ASC""",
            mapOf("from" to Timestamp.from(from.atStartOfDay(zone).toInstant()),"to" to Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()),"types" to types.map { it.name })) { rs,_ ->
                PushActivityReach(PushDeliveryType.valueOf(rs.getString("type")),LocalDate.ofEpochDay(rs.getLong("epoch_day")),rs.getLong("reached"))
            }
    }

    /** 캠페인 선점 전의 결과를 제외하고 마지막 회차의 도달 회원 시각을 제공한다. */
    fun reachedMemberDeliveries(campaignId: UUID): List<PushMemberDelivery> = jdbc.query("""
        SELECT r.user_id,r.type,r.completed_at FROM push_delivery_results r
        JOIN push_campaigns c ON c.id=r.campaign_id
        WHERE c.public_id=? AND c.started_at IS NOT NULL AND r.completed_at>=c.started_at
            AND r.delivered_devices>0 AND r.user_id IS NOT NULL
        """, { rs, _ -> PushMemberDelivery(rs.getLong("user_id"), PushDeliveryType.valueOf(rs.getString("type")),
            rs.getTimestamp("completed_at").toInstant(), campaignId) }, campaignId)

    fun reachedMemberDeliveries(from: LocalDate, to: LocalDate, types: List<PushDeliveryType>): List<PushMemberDelivery> {
        if (types.isEmpty()) return emptyList()
        val zone = ZoneId.of("Asia/Seoul")
        return org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
            SELECT user_id,type,completed_at FROM push_delivery_results
            WHERE completed_at>=:from AND completed_at<:to AND type IN (:types)
                AND delivered_devices>0 AND user_id IS NOT NULL
            """, mapOf("from" to Timestamp.from(from.atStartOfDay(zone).toInstant()),
                "to" to Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()), "types" to types.map { it.name })) { rs, _ ->
            PushMemberDelivery(rs.getLong("user_id"), PushDeliveryType.valueOf(rs.getString("type")), rs.getTimestamp("completed_at").toInstant())
        }
    }

}
