package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditOrder
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CreditOrderRepository : JpaRepository<CreditOrder, Long> {
    fun findByPublicId(publicId: UUID): CreditOrder?
}
