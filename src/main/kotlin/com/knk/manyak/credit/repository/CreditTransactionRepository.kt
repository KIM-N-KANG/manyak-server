package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditTransaction
import org.springframework.data.jpa.repository.JpaRepository

interface CreditTransactionRepository : JpaRepository<CreditTransaction, Long> {

    // 보상 멱등: 같은 키의 원장 행이 이미 있으면 중복 적립하지 않는다(유니크 제약과 함께 이중 방어).
    fun existsByIdempotencyKey(idempotencyKey: String): Boolean
}
