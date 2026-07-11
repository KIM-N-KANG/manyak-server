package com.knk.manyak.credit.service

import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 대사 1회 결과. */
data class ReconciliationResult(val groupsScanned: Int, val refundsEmitted: Int)

/**
 * 크레딧 선차감 대사(reconciliation) — 유실 환불 사후 복구(스펙 §4-3-7, KNK-448).
 *
 * in-flight 차감/환불(KNK-398·399)은 흔한 실패를 처리하고, 이 배치는 그 경로가 놓친 드문 유실을 backstop한다:
 * - 채팅 턴: executor 백로그로 큐 대기 중 SSE 타임아웃·취소로 워커가 실행 자체를 못 해 환불이 누락된 경우.
 * - 공통: 선차감 커밋 직후 프로세스 중단으로 환불 행이 누락된 경우.
 *
 * 대사 모델(개수 대조): 소모·환불이 모두 같은 coarse ref(CHAT→chatPk, STORY→session.id)를 가리켜 행별 매칭이
 * 불가하므로 (userId, refType, refId) 그룹 단위로 센다. 그룹의 **있어야 할 총 환불 수 = charge 수 − 완료 수**이고,
 * 모자란 만큼만 [CreditWalletService.reconcileRefunds]가 지갑 락 아래 발행한다(초과 환불 없음, 멱등).
 *
 * 완료 수 판정:
 * - CHAT: `chat.currentTurn + chat.regeneratedCount`(저장된 턴 수 + 완료된 재생성 수, KNK-406). 재생성은 유료
 *   (CHAT_TURN) charge지만 current_turn을 올리지 않으므로 regeneratedCount를 더해야 성공한 재생성이 초과 환불되지
 *   않는다. 게스트·회원이 섞인 채팅은 완료 수가 회원 charge보다 클 수 있어 과대평가되면 target이 음수→0이 되어
 *   미환불된다(fail-safe: 서버가 절대 초과 환불하지 않음, 순수 회원 채팅은 정확). 리소스가 사라졌으면(삭제) 완료로 간주해 환불하지 않는다.
 * - STORY: 세션 status가 STORY_CREATED면 1, 아니면 0(세션은 charge보다 먼저 생기므로 유실 charge엔 항상 존재).
 */
@Service
class CreditReconciliationService(
    private val transactionRepository: CreditTransactionRepository,
    private val creditWalletService: CreditWalletService,
    private val storyChatRepository: StoryChatRepository,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val structuredLogger: StructuredLogger,
    // 그룹의 마지막 charge가 이 시간보다 오래됐을 때만 대사한다(in-flight 경합 배제). SSE 타임아웃(60s)·컴파일(수초)보다 충분히 크게.
    @param:Value("\${manyak.credit.reconciliation.charge-age-threshold:PT15M}")
    private val chargeAgeThreshold: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** 정지 상태 그룹을 대사해 유실 환불을 보정한다. 반환값은 스캔한 그룹 수와 이번에 발행한 환불 행 수. */
    fun reconcile(): ReconciliationResult {
        val cutoff = Instant.now(clock).minus(chargeAgeThreshold)
        val groups = transactionRepository.findStuckChargeGroups(CONSUMPTION_REASONS, cutoff)
        var refundsEmitted = 0
        for (group in groups) {
            // 그룹 단위로 격리한다: 한 그룹의 실패(데이터 이상·DB 오류)가 나머지 그룹 대사를 막지 않게 하고,
            // 멱등하므로 다음 회차에 다시 시도된다.
            try {
                val completed = completedCount(group.refType, group.refId)
                // 있어야 할 총 환불 수 = charge − 완료. 음수(완료 과다)면 발행하지 않는다(fail-safe).
                val targetRefundCount = group.chargeCount - completed
                if (targetRefundCount <= 0) continue
                refundsEmitted += creditWalletService.reconcileRefunds(
                    userId = group.userId,
                    refType = group.refType,
                    refId = group.refId,
                    unitAmount = group.unitAmount,
                    targetRefundCount = targetRefundCount,
                )
            } catch (exception: Exception) {
                structuredLogger.event(
                    "credit_reconciliation_group_failed",
                    "user_id" to group.userId,
                    "ref_type" to group.refType,
                    "ref_id" to group.refId,
                    "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
                )
            }
        }
        return ReconciliationResult(groupsScanned = groups.size, refundsEmitted = refundsEmitted)
    }

    /** 그룹의 완료 수. 판정 불가(리소스 삭제·미지원 refType)는 완료 과다로 처리해 환불하지 않는다(fail-safe). */
    private fun completedCount(refType: String, refId: Long): Long =
        when (refType) {
            // 완료 수 = 완료 턴 수(current_turn) + 완료 재생성 수(regenerated_count). 재생성은 current_turn을 올리지
            // 않는 유료 CHAT_TURN charge라, 재생성 완료분을 더하지 않으면 성공한 재생성이 초과 환불된다(KNK-406).
            CHAT_REF_TYPE ->
                storyChatRepository.findById(refId)
                    .map { (it.currentTurn + it.regeneratedCount).toLong() }
                    .orElse(NEVER_REFUND)
            STORY_REF_TYPE ->
                storyCreationSessionRepository.findById(refId)
                    .map { if (it.status == StoryCreationSessionStatus.STORY_CREATED) 1L else 0L }
                    .orElse(NEVER_REFUND)
            else -> NEVER_REFUND
        }

    private companion object {
        val CONSUMPTION_REASONS = listOf(CreditReason.STORY_CREATION, CreditReason.CHAT_TURN)

        // 소모 행의 ref_type. 소비자(ChatService·SimpleStoryCreationService)의 차감 refType과 일치해야 한다.
        const val CHAT_REF_TYPE = "CHAT"
        const val STORY_REF_TYPE = "STORY"

        // 완료 수를 사실상 무한대로 둬 target(=charge−완료)을 음수로 만들어 환불하지 않게 하는 값(fail-safe).
        const val NEVER_REFUND = Long.MAX_VALUE
    }
}
