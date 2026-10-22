package com.knk.manyak.credit.service

import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryMessageVersionRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChatChargeReconciler(
    private val transactions: CreditTransactionRepository,
    private val messages: StoryMessageRepository,
    private val versions: StoryMessageVersionRepository,
    private val wallet: CreditWalletService,
) {
    /** 차감 행 → 지갑 순서. 저장은 채팅 → 같은 차감 행 순서로 잠근다. */
    @Transactional
    fun reconcile(id: Long): Boolean {
        val charge = transactions.findByIdForUpdate(id) ?: return false
        check(charge.reason == CreditReason.CHAT_TURN && charge.amount < 0)
        if (charge.refType != "CHAT_TURN") return false
        val key = "refund:charge:$id"
        if (messages.existsByCreditTransactionId(id) || versions.existsByCreditTransactionId(id) ||
            transactions.existsByIdempotencyKey(key)
        ) return false
        return wallet.reward(charge.userId, -charge.amount, CreditReason.REFUND, key, charge.refType, charge.refId).rewarded
    }
}
