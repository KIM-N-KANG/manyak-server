package com.knk.manyak.push.service

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.repository.PushCampaignRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 캠페인 한 건의 회차 결과. [targets]는 조회된 대상 수, [sent]는 실제로 발송기를 부른 수, [skipped]는 조회
 * 이후 자격을 잃어(동의 철회·정지·탈퇴·야간 미동의) 건너뛴 수다. [failed]는 회차가 예외로 끝났다는 뜻이다.
 */
data class PromotionPushResult(
    val campaignId: UUID,
    val targets: Int,
    val sent: Int,
    val skipped: Int,
    val failed: Boolean = false,
)

/**
 * 프로모션 푸시 발송(KNK-1117, 스펙 §4-3-5). **광고성 알림**이라 사전 동의가 필수다(정책 KNK-1129).
 *
 * 트리거는 운영자 SQL이다 — `push_campaigns`에 `SCHEDULED` 행을 넣으면 예약이고, 이 서비스가 도래한 행을
 * 집어 보낸다. 관리자 API는 두지 않는다.
 *
 * **선점은 DB 행이 한다**([PushCampaignRepository.claim]). 배포 교체로 태스크가 둘일 때 같은 캠페인이 두 번
 * 나가는 것을 조건부 UPDATE 하나가 막으므로, 출석 리마인드가 쓰는 Redis `SET NX`가 여기서는 필요 없다.
 *
 * **동의는 발송 직전에 다시 읽는다**([AttendanceReminderService]와 같은 이유). 대상 조회 결과는 스냅샷이라
 * 회차가 도는 동안의 철회·정지·탈퇴가 반영되지 않는다. 야간(21~08시 KST) 판정도 여기서 한다 — 야간에 예약된
 * 캠페인을 거부·연기하는 게 아니라 **야간 동의자에게만** 보내고 나머지는 `skipped`로 남긴다.
 *
 * 트랜잭션을 열지 않는다. 발송은 외부 IO(FCM)라 트랜잭션 안에 두면 회차 내내 커넥션을 쥔 채 네트워크를
 * 기다린다. 리포지토리 호출 각각이 자기 트랜잭션으로 돈다.
 *
 * ponytail: 회차 중간에 태스크가 죽으면 행이 `SENDING`으로 남고 재개 로직은 없다. 운영자가 새 행을 넣는다
 * (남은 회원만 골라 보낼 수 없어 일부 중복 가능 — 캠페인 빈도가 낮아 수용). 재개가 필요해지면 그때 회원별
 * 발송 기록을 둔다.
 */
@Service
class PromotionPushService(
    private val pushCampaignRepository: PushCampaignRepository,
    private val userRepository: UserRepository,
    private val fcmPushSender: FcmPushSender,
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
        var skipped = 0
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
            targetIds.forEach { userId ->
                // 발송 직전 재조회. 스냅샷을 믿으면 회차 도중의 철회·정지·탈퇴가 반영되지 않는다.
                val user = userRepository.findById(userId).orElse(null)
                if (user == null || user.status != UserStatus.ACTIVE || !user.canReceiveMarketingPush(now)) {
                    skipped++
                    return@forEach
                }
                // 한 회원의 발송 실패가 나머지 회차를 끊지 않는다. 개별 토큰 실패는 FcmPushSender가 이미 흡수한다.
                runCatching { fcmPushSender.sendToUser(userId, data) }
                    .onSuccess { sent++ }
                    .onFailure { logger.warn("프로모션 푸시 발송에 실패했습니다. (userId={}, error={})", userId, it.javaClass.simpleName) }
            }
            finish(campaign, PushCampaignStatus.SENT, targets, sent, skipped)
            PromotionPushResult(campaign.publicId, targets, sent, skipped)
        } catch (exception: Exception) {
            // 회차 자체가 끝나지 못한 경우(예: 대상 조회 실패). 행을 SCHEDULED로 되돌리지 않는다 — 그러면
            // 다음 회차가 같은 실패를 무한히 반복한다. 운영자가 원인을 보고 새 행을 넣는 편이 낫다.
            logger.warn("프로모션 푸시 회차가 실패했습니다. (campaignId={})", campaign.publicId, exception)
            finish(campaign, PushCampaignStatus.FAILED, targets, sent, skipped)
            PromotionPushResult(campaign.publicId, targets, sent, skipped, failed = true)
        }
    }

    private fun finish(campaign: PushCampaign, status: PushCampaignStatus, targets: Int, sent: Int, skipped: Int) {
        campaign.status = status
        campaign.targetCount = targets
        campaign.sentCount = sent
        campaign.skippedCount = skipped
        // 회차 길이를 보려면 끝난 시각은 논리 시각(now)이 아니라 실제 시각이어야 한다.
        campaign.finishedAt = Instant.now()
        pushCampaignRepository.save(campaign)
    }

    private companion object {
        /** 앱이 알림 UI를 조립할 때 쓰는 시나리오 식별자(data 전용 메시지 — KNK-1130). */
        const val TYPE_PROMOTION = "PROMOTION"
    }
}
