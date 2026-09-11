package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditOrder
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface CreditOrderRepository : JpaRepository<CreditOrder, Long> {
    fun findByPublicId(publicId: UUID): CreditOrder?
    fun findByProviderRef(providerRef: String): CreditOrder?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM CreditOrder o WHERE o.publicId = :publicId")
    fun findByPublicIdForUpdate(publicId: UUID): CreditOrder?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM CreditOrder o WHERE o.providerRef = :providerRef")
    fun findByProviderRefForUpdate(providerRef: String): CreditOrder?
}
