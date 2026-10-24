package com.knk.manyak.push.service

import com.google.firebase.messaging.AndroidConfig
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.config.PushCampaignProperties
import com.knk.manyak.global.observability.StructuredLogger
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.time.temporal.ChronoUnit
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.push.outbox.ScheduledPushOutboxWriter
import com.knk.manyak.story.repository.StoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 원격 모드의 sent는 기록한 요청 수, 로컬 모드는 발송기 호출 수다. */
data class PromotionPushResult(
    val campaignId: UUID,
    val targets: Int,
    val sent: Int,
    val failed: Boolean = false,
    val kind: PushKind = PushKind.MARKETING,
)

/** 캠페인을 독립 선점하고, 원격 모드는 회차의 요청을 한 트랜잭션에 기록한다. */
@Service
@EnableConfigurationProperties(PushCampaignProperties::class)
class PromotionPushService(
    private val pushCampaignRepository: PushCampaignRepository,
    private val userRepository: UserRepository,
    private val fcmPushSender: FcmPushSender,
    private val stories: StoryRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val outbox: ScheduledPushOutboxWriter? = null,
    private val properties: PushCampaignProperties = PushCampaignProperties(),
    private val structuredLogger: StructuredLogger = StructuredLogger(),
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val webBaseUrl: String = "https://manyak.app",
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** [now] 기준으로 도래한 예약을 예약 순으로 발송한다. 선점에 실패한 캠페인은 결과에 담기지 않는다. */
    fun sendDue(now: Instant): List<PromotionPushResult> {
        val startedAt = now.truncatedTo(ChronoUnit.MICROS)
        // 정리 실패가 같은 틱의 예약 선점·발송을 막지 않도록 경계를 분리한다.
        try {
            failTimedOut(startedAt)
        } catch (exception: Exception) {
            structuredLogger.event(
                "promotion_push_failed",
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
        }
        return pushCampaignRepository.findDueScheduled(startedAt).mapNotNull { campaign ->
            // 앞 캠페인 처리가 길어져도 다음 캠페인의 TTL은 실제 선점부터 센다.
            val claimedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
            if (pushCampaignRepository.claim(campaign.id, claimedAt) == 1) send(campaign, claimedAt) else null
        }
    }

    private fun failTimedOut(startedAt: Instant) {
        // 타임아웃을 예약 조회보다 먼저 처리하며 자동으로 재실행하지 않는다.
        pushCampaignRepository.findByStatusAndStartedAtLessThanEqual(PushCampaignStatus.SENDING, startedAt.minus(properties.sendingTtl))
            .forEach { campaign ->
                if (pushCampaignRepository.finishIfOwned(campaign.id, campaign.startedAt!!, PushCampaignStatus.FAILED,
                        campaign.targetCount, campaign.sentCount, startedAt) == 1) {
                    logger.warn("promotion_push_timed_out campaignId={} startedAt={}", campaign.publicId, campaign.startedAt)
                }
            }
    }

    private fun send(campaign: PushCampaign, now: Instant): PromotionPushResult? {
        campaign.startedAt = now
        var targets = 0
        var sent = 0
        return try {
            val targetIds = if (campaign.kind == PushKind.SERVICE) userRepository.findServicePushTargetIds() else userRepository.findMarketingPushTargetIds()
            targets = targetIds.size
            // 문구는 회차 시작에 한 번만 조립한다. (광고) 표기는 서버가 붙인다(DB 값에 맡기지 않는다).
            val data = mutableMapOf(
                "type" to if (campaign.kind == PushKind.SERVICE) "NOTICE" else TYPE_PROMOTION,
                "campaignId" to campaign.publicId.toString(),
                "title" to if (campaign.kind == PushKind.SERVICE) campaign.title else withAdPrefix(campaign.title),
                "body" to campaign.body,
            )
            campaign.noticeId?.let { id ->
                val publicId = requireNotNull(pushCampaignRepository.findNoticePublicId(id)) { "공지 참조가 없습니다." }
                data["noticeId"] = publicId.toString()
                data["deepLink"] = pushDeepLink(webBaseUrl, "/notices/$publicId", data.getValue("type"), campaign.publicId.toString())
            }
            if (outbox != null) {
                sent = outbox.record(targetIds, withStoryDestination(campaign, data),
                    if (campaign.kind == PushKind.SERVICE) "notice" else "promotion", campaign.publicId.toString(), kind = campaign.kind.name)
            } else targetIds.forEach { userId ->
                val messagePrefix = if (campaign.kind == PushKind.SERVICE) "notice" else "promotion"
                // 로컬 모드 롤백 경로는 발송 직전에 자격과 야간 여부를 확인한다.
                val at = clock.instant()
                val user = userRepository.findById(userId).orElse(null)
                if (user == null || user.status != UserStatus.ACTIVE || (if (campaign.kind == PushKind.SERVICE) !user.servicePushEnabled else !user.canReceiveMarketingPush(at))) {
                    fcmPushSender.recordDiscarded(userId, data, "$messagePrefix:${campaign.publicId}:${user?.publicId}",
                        if (user?.status != UserStatus.ACTIVE) "NOT_ACTIVE"
                        else if (campaign.kind == PushKind.SERVICE) "SERVICE_PUSH_DISABLED" else "MARKETING_NOT_AGREED")
                    return@forEach
                }
                // 한 회원의 발송 실패가 나머지 회차를 끊지 않는다. 개별 토큰 실패는 FcmPushSender가 이미 흡수한다.
                val deliveryData = withStoryDestination(campaign, data)
                runCatching { fcmPushSender.sendToUser(userId, deliveryData, AndroidConfig.Priority.NORMAL,
                    messageId = "$messagePrefix:${campaign.publicId}:${user.publicId}") }
                    .onSuccess { sent++ }
                    .onFailure { logger.warn("프로모션 푸시 발송에 실패했습니다. (userId={}, error={})", userId, it.javaClass.simpleName) }
            }
            if (finish(campaign, PushCampaignStatus.SENT, targets, sent))
                PromotionPushResult(campaign.publicId, targets, sent, kind = campaign.kind) else null
        } catch (exception: Exception) {
            // 자동 재시도하지 않는다. 운영자가 같은 행을 SCHEDULED로 되돌리면 멱등하게 재실행한다.
            logger.warn("프로모션 푸시 회차가 실패했습니다. (campaignId={})", campaign.publicId, exception)
            if (finish(campaign, PushCampaignStatus.FAILED, targets, sent))
                PromotionPushResult(campaign.publicId, targets, sent, failed = true, kind = campaign.kind) else null
        }
    }

    private fun withStoryDestination(campaign: PushCampaign, data: Map<String, String>): Map<String, String> {
        // 예약 당시 공개였어도 지금은 비공개·초안·삭제 상태일 수 있다. 소유자 열람 예외는 적용하지 않는다.
        // 공지 캠페인의 이동 목적지는 기존 공지 링크를 유지한다.
        if (campaign.kind != PushKind.MARKETING || campaign.noticeId != null) return data
        val storyId = campaign.storyId ?: return data
        val story = stories.findByIdAndDeletedAtIsNull(storyId)?.takeIf { it.isPubliclyVisible() } ?: return data
        return data + mapOf("storyId" to story.publicId.toString(),
            "deepLink" to pushDeepLink(webBaseUrl, "/stories/${story.publicId}", TYPE_PROMOTION, campaign.publicId.toString()))
    }

    private fun finish(campaign: PushCampaign, status: PushCampaignStatus, targets: Int, sent: Int): Boolean {
        val updated = pushCampaignRepository.finishIfOwned(campaign.id, campaign.startedAt!!, status, targets, sent, clock.instant())
        if (updated == 0) logger.warn("promotion_push_result_discarded campaignId={} startedAt={}", campaign.publicId, campaign.startedAt)
        return updated == 1
    }

    private companion object {
        /** 앱이 알림 UI를 조립할 때 쓰는 시나리오 식별자(data 전용 메시지 — KNK-1130). */
        const val TYPE_PROMOTION = "PROMOTION"
    }
}
