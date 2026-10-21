package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditAdminAdjustment
import org.springframework.data.jpa.repository.JpaRepository

interface CreditAdminAdjustmentRepository : JpaRepository<CreditAdminAdjustment, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): CreditAdminAdjustment?
}
