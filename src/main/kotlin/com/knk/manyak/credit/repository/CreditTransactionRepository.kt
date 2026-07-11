package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface CreditTransactionRepository : JpaRepository<CreditTransaction, Long> {

    // 보상 멱등: 같은 키의 원장 행이 이미 있으면 중복 적립하지 않는다(유니크 제약과 함께 이중 방어).
    fun existsByIdempotencyKey(idempotencyKey: String): Boolean

    // 한 그룹(userId, refType, refId)의 특정 사유 행 수. 대사에서 이미 존재하는 REFUND 수를 재확인하는 데 쓴다.
    fun countByUserIdAndRefTypeAndRefIdAndReason(
        userId: Long,
        refType: String,
        refId: Long,
        reason: CreditReason,
    ): Long

    // 초대 보상 월 상한 판정(스펙 §4-3-7, KNK-477): 보상 수령 계정의 특정 사유 행을 [start, end) 구간(KST 월)으로 집계한다.
    fun countByUserIdAndReasonAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
        userId: Long,
        reason: CreditReason,
        start: Instant,
        end: Instant,
    ): Long

    /**
     * 대사(reconciliation) 후보 그룹을 찾는다(스펙 §4-3-7, KNK-448).
     *
     * 소모 행([reasons]=STORY_CREATION·CHAT_TURN)을 (userId, refType, refId)로 묶어 charge 수와 최소 차감액을 낸다.
     * [cutoff]보다 최신 charge가 있는 그룹은 제외한다(HAVING MAX): in-flight 차감/환불이 아직 끝나지 않았을 수 있어
     * 지금 대사하면 진행 중 턴을 성급히 환불할 수 있다. 그룹의 마지막 charge가 [cutoff] 이전이면 그 그룹의 모든
     * 차감은 이미 완료·환불·유실 중 하나로 확정됐으므로(정지 상태) 개수 대조가 정확하다.
     *
     * 환불 단위액은 `MIN(ABS(amount))`이다 — 균일 단가에선 실제 단가라 정확하고, 혼합 단가(드묾)에선 서버가
     * 초과 환불하지 않도록 의도적으로 보수 편향한다(근거는 [StuckChargeGroup] 참고).
     */
    @Query(
        """
        SELECT new com.knk.manyak.credit.repository.StuckChargeGroup(
            t.userId, t.refType, t.refId, COUNT(t), MIN(ABS(t.amount)))
        FROM CreditTransaction t
        WHERE t.reason IN :reasons AND t.refType IS NOT NULL AND t.refId IS NOT NULL
        GROUP BY t.userId, t.refType, t.refId
        HAVING MAX(t.createdAt) < :cutoff
        """,
    )
    fun findStuckChargeGroups(
        @Param("reasons") reasons: Collection<CreditReason>,
        @Param("cutoff") cutoff: Instant,
    ): List<StuckChargeGroup>
}
