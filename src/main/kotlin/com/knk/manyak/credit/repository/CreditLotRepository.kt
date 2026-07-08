package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditLot
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface CreditLotRepository : JpaRepository<CreditLot, Long> {

    /**
     * FIFO 차감·만료 정리용: 잔여>0 로트를 비관적 락으로 잠그고 소비 순서로 정렬해 조회한다(스펙 §4-3-7).
     *
     * 정렬: 유효기간 있는 로트(만료 임박=`expires_at` 오름차순) → 무기한(NULL) 로트, 동률은 `id`(먼저 적립). 만료된
     * 로트는 `expires_at`이 과거라 맨 앞에 온다 — 호출부가 앞에서부터 만료 정리 후 남은 활성 로트를 소진한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT l FROM CreditLot l
        WHERE l.userId = :userId AND l.remaining > 0
        ORDER BY CASE WHEN l.expiresAt IS NULL THEN 1 ELSE 0 END ASC, l.expiresAt ASC, l.id ASC
        """,
    )
    fun findActiveForUpdate(@Param("userId") userId: Long): List<CreditLot>

    /**
     * 활성 잔액(미만료·잔여>0 로트 잔여의 합). 만료분을 [now] 기준으로 제외하는 부수효과 없는 조회다 —
     * 아직 EXPIRE 행으로 정리되지 않은 만료 로트도 잔액에서 빠진다(스펙 §4-3-5 `creditBalance`).
     */
    @Query(
        """
        SELECT COALESCE(SUM(l.remaining), 0) FROM CreditLot l
        WHERE l.userId = :userId AND l.remaining > 0
          AND (l.expiresAt IS NULL OR l.expiresAt > :now)
        """,
    )
    fun sumActiveRemaining(@Param("userId") userId: Long, @Param("now") now: Instant): Long
}
