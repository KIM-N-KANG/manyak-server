package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditWallet
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CreditWalletRepository : JpaRepository<CreditWallet, Long> {

    fun findByUserId(userId: Long): CreditWallet?

    /**
     * 지갑 행을 비관적 쓰기 락으로 조회한다(스펙 §4-3-7). 차감·적립의 balance 갱신을 같은 지갑에 대해 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM CreditWallet w WHERE w.userId = :userId")
    fun findByUserIdForUpdate(@Param("userId") userId: Long): CreditWallet?
}
