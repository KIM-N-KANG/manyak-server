package com.knk.manyak.credit.service

import com.knk.manyak.credit.dto.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 유료 턴 저장의 차감 행 잠금(CHAT 5-2-1, PAYMENT 4-1-15, KNK-2080). 채팅 저장 트랜잭션이 채팅 행을 잠근 다음에 부른다.
 * 선차감 대사([ChatChargeReconciler])가 같은 차감 행을 잠그므로 저장과 대사 환불이 함께 성립하지 않는다. 지갑은 잠그지 않는다.
 *
 * MANDATORY라 트랜잭션 밖 호출은 잠그지 않고 실패한다. 잠금은 호출자 트랜잭션이 끝날 때 풀린다.
 */
@Component
class ChatTurnChargeLock(private val transactions: CreditTransactionRepository) {

    /** [chargeId] 차감 행을 잠그고, 이미 환불됐으면(`refund:charge:{id}` 행 존재) true. 행이 없거나 채팅 턴 차감이 아니면 예외. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockAndCheckRefunded(chargeId: Long): Boolean {
        val charge = transactions.findByIdForUpdate(chargeId) ?: error("차감 행이 없습니다: $chargeId")
        check(charge.reason == CreditReason.CHAT_TURN && charge.amount < 0)
        return transactions.existsByIdempotencyKey("refund:charge:$chargeId")
    }
}
