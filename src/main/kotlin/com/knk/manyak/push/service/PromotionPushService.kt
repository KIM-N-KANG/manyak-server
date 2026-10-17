package com.knk.manyak.push.service

import com.google.firebase.messaging.AndroidConfig
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.push.outbox.ScheduledPushOutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 원격 모드의 sent는 기록한 요청 수, 로컬 모드는 발송기 호출 수다. */
data class PromotionPushResult(
    val campaignId: UUID,
    val targets: Int,
    val sent: Int,
    val failed: Boolean = false,
)

/** 캠페인을 독립 선점하고, 원격 모드는 회차의 요청을 한 트랜잭션에 기록한다. */
@Service
class PromotionPushService(
    private val pushCampaignRepository: PushCampaignRepository,
    private val userRepository: UserRepository,
    private val fcmPushSender: FcmPushSender,
    private val clock: Clock = Clock.systemUTC(),
    private val outbox: ScheduledPushOutboxWriter? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** [now] 기준으로 도래한 예약을 예약 순으로 발송한다. 선점에 실패한 캠페인은 결과에 담기지 않는다. */
    fun sendDue(now: Instant): List<PromotionPushResult> =
        pushCampaignRepository.findDueScheduled(now).mapNotNull { campaign ->
            // 갱신 0건이면 다른 인스턴스가 이미 집었거나 그 사이 운영자가 취소한 것이다.
            if (pushCampaignRepository.claim(campaign.id, now) == 1) send(campaign, now) else null
        }

    private fun send(campaign: PushCampaign, now: Instant): PromotionPushResult {
        campaign.startedAt = now
        var targets = 0
        var sent = 0
        return try {
            val targetIds = userRepository.findMarketingPushTargetIds()
            targets = targetIds.size
            // 문구는 회차 시작에 한 번만 조립한다. (광고) 표기는 서버가 붙인다(DB 값에 맡기지 않는다).
            val data = mapOf(
                "type" to TYPE_PROMOTION,
                "campaignId" to campaign.publicId.toString(),
                "title" to withAdPrefix(campaign.title),
                "body" to campaign.body,
            )
            if (outbox != null) {
                sent = outbox.record(targetIds, data, "promotion", campaign.publicId.toString())
            } else targetIds.forEach { userId ->
                // 로컬 모드 롤백 경로는 발송 직전에 자격과 야간 여부를 확인한다.
                val at = clock.instant()
                val user = userRepository.findById(userId).orElse(null)
                if (user == null || user.status != UserStatus.ACTIVE || !user.canReceiveMarketingPush(at)) {
                    return@forEach
                }
                // 한 회원의 발송 실패가 나머지 회차를 끊지 않는다. 개별 토큰 실패는 FcmPushSender가 이미 흡수한다.
                runCatching { fcmPushSender.sendToUser(userId, data, AndroidConfig.Priority.NORMAL) }
                    .onSuccess { sent++ }
                    .onFailure { logger.warn("프로모션 푸시 발송에 실패했습니다. (userId={}, error={})", userId, it.javaClass.simpleName) }
            }
            finish(campaign, PushCampaignStatus.SENT, targets, sent)
            PromotionPushResult(campaign.publicId, targets, sent)
        } catch (exception: Exception) {
            // 자동 재시도하지 않는다. 운영자가 같은 행을 SCHEDULED로 되돌리면 멱등하게 재실행한다.
            logger.warn("프로모션 푸시 회차가 실패했습니다. (campaignId={})", campaign.publicId, exception)
            finish(campaign, PushCampaignStatus.FAILED, targets, sent)
            PromotionPushResult(campaign.publicId, targets, sent, failed = true)
        }
    }

    private fun finish(campaign: PushCampaign, status: PushCampaignStatus, targets: Int, sent: Int) {
        campaign.status = status
        campaign.targetCount = targets
        campaign.sentCount = sent
        // 회차 길이를 보려면 끝난 시각은 논리 시각(now)이 아니라 실제 시각이어야 한다.
        campaign.finishedAt = clock.instant()
        pushCampaignRepository.save(campaign)
    }

    private companion object {
        /** 앱이 알림 UI를 조립할 때 쓰는 시나리오 식별자(data 전용 메시지 — KNK-1130). */
        const val TYPE_PROMOTION = "PROMOTION"
    }
}
