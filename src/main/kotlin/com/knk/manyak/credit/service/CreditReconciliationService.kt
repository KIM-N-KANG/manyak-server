package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 대사 1회 결과. */
data class ReconciliationResult(val groupsScanned: Int, val refundsEmitted: Int, val chargesScanned: Int = 0)

/** CHAT_TURN 참조는 행별로, CHAT 참조와 간편 제작은 그룹별로 대사한다. */
@Service
class CreditReconciliationService(
    private val transactionRepository: CreditTransactionRepository,
    private val creditWalletService: CreditWalletService,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val structuredLogger: StructuredLogger,
    // 그룹의 마지막 charge가 이 시간보다 오래됐을 때만 대사한다(in-flight 경합 배제). SSE 타임아웃(60s)·컴파일(수초)보다 충분히 크게.
    @param:Value("\${manyak.credit.reconciliation.charge-age-threshold:PT15M}")
    private val chargeAgeThreshold: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val chargeReconciler: ChatChargeReconciler,
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
                val completed = completedCount(group.userId, group.refType, group.refId)
                // 있어야 할 총 환불 수 = charge − 완료. 음수(완료 과다)면 발행하지 않는다(fail-safe).
                val targetRefundCount = group.chargeCount - completed
                if (targetRefundCount <= 0) continue
                val emitted = creditWalletService.reconcileRefunds(
                    userId = group.userId,
                    refType = group.refType,
                    refId = group.refId,
                    unitAmount = group.unitAmount,
                    targetRefundCount = targetRefundCount,
                )
                refundsEmitted += emitted
                // 혼합 단가 탐지(KNK-1056): 정책 오버라이드가 채팅/세션 수명 도중 바뀌면 그룹 안에 서로 다른
                // 차감액이 섞인다. 환불은 계속 최소액으로(초과 환불 금지) 내되, 그때 회원이 단가 차액만큼
                // 덜 받는다는 사실이 조용히 지나가지 않도록 남긴다. 계산은 바꾸지 않는다 — 관측만 추가한다.
                //
                // **실제로 환불을 발행했을 때만** 경고한다(Codex 리뷰 P2). targetRefundCount는 in-flight 경로가
                // 이미 각 차감액대로 정확히 환불했어도 양수로 남고, 발행 여부는 reconcileRefunds 안에서야
                // 판정된다. 그 앞에서 경고하면 미보상이 없는 그룹까지 매 배치마다 같은 오경보를 반복해
                // "조용한 미보상을 드러낸다"는 목적 자체가 신호에 묻힌다.
                if (emitted > 0 && group.unitAmount != group.maxUnitAmount) {
                    structuredLogger.warn(
                        "credit_reconciliation_mixed_unit_amount",
                        "user_id" to group.userId,
                        "ref_type" to group.refType,
                        "ref_id" to group.refId,
                        "min_unit_amount" to group.unitAmount,
                        "max_unit_amount" to group.maxUnitAmount,
                        "refunds_emitted" to emitted,
                    )
                }
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
        var afterId = 0L
        var chargesScanned = 0
        while (true) {
            val charges = transactionRepository.findChatTurnCharges(cutoff, afterId, PageRequest.of(0, CHAT_TURN_CANDIDATE_LIMIT))
            if (charges.isEmpty()) break
            chargesScanned += charges.size
            for (charge in charges) {
                try {
                    if (chargeReconciler.reconcile(charge.id)) refundsEmitted++
                } catch (exception: Exception) {
                    structuredLogger.event(
                        "credit_reconciliation_charge_failed",
                        "user_id" to charge.userId,
                        "transaction_id" to charge.id,
                        "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
                    )
                }
            }
            // 실패 행도 커서를 전진시켜 뒤의 정상 행을 같은 실행에서 처리한다.
            afterId = charges.last().id
        }
        return ReconciliationResult(groupsScanned = groups.size, refundsEmitted = refundsEmitted, chargesScanned = chargesScanned)
    }

    /** 그룹의 완료 수. 판정 불가(리소스 삭제·미지원 refType)는 완료 과다로 처리해 환불하지 않는다(fail-safe). */
    private fun completedCount(userId: Long, refType: String, refId: Long): Long =
        when (refType) {
            // 완료 수 = 완료 턴 수(current_turn) + 완료 재생성 수(regenerated_count). 재생성은 current_turn을 올리지
            // 않는 유료 CHAT_TURN charge라, 재생성 완료분을 더하지 않으면 성공한 재생성이 초과 환불된다(KNK-406).
            CHAT_REF_TYPE -> transactionRepository.findLegacyChatCompletedCount(userId, refId)
                ?.coerceAtLeast(0) ?: NEVER_REFUND
            STORY_REF_TYPE ->
                storyCreationSessionRepository.findById(refId)
                    .map { if (it.status == StoryCreationSessionStatus.STORY_CREATED) 1L else 0L }
                    .orElse(NEVER_REFUND)
            else -> NEVER_REFUND
        }

    private companion object {
        const val CHAT_TURN_CANDIDATE_LIMIT = 500
        val CONSUMPTION_REASONS = listOf(CreditReason.STORY_CREATION, CreditReason.CHAT_TURN)

        // 소모 행의 ref_type. 소비자(ChatService·SimpleStoryCreationService)의 차감 refType과 일치해야 한다.
        const val CHAT_REF_TYPE = "CHAT"
        const val STORY_REF_TYPE = "STORY"

        // 완료 수를 사실상 무한대로 둬 target(=charge−완료)을 음수로 만들어 환불하지 않게 하는 값(fail-safe).
        const val NEVER_REFUND = Long.MAX_VALUE
    }
}
