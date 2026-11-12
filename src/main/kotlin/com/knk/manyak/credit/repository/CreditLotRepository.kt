package com.knk.manyak.credit.repository

import com.knk.manyak.credit.dto.CreditExpiryTarget
import com.knk.manyak.credit.entity.CreditLot
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.OffsetDateTime

interface CreditLotRepository : JpaRepository<CreditLot, Long> {

    /** 창 안 잔여 로트를 회원별로 모으고 가장 늦은 만료 시각을 발송 유효 기한으로 쓴다. 회원 id 오름차순이다. */
    fun findExpiryReminderTargets(now: Instant, until: Instant): List<CreditExpiryTarget> =
        findExpiryReminderTargetRows(now, until).map { ExpiryTarget((it[0] as Number).toLong(), instantOf(it[1])) }

    /**
     * [findExpiryReminderTargets]의 원시 행(회원 id, 가장 늦은 만료 시각). 활성 회원만이다(`users.status`는 PAYMENT 3-2
     * "참조하는 다른 도메인의 컬럼"). KNK-2056 전 JPQL과 같은 조건, 같은 정렬이다. 시각 열의 자바 타입이 드라이버마다 달라
     * (H2는 `OffsetDateTime`, PostgreSQL은 `Instant`) 투영 대신 원시 행으로 받아 조립한다.
     */
    @Query(
        value = """
        SELECT l.user_id, MAX(l.expires_at) FROM credit_lots l
        JOIN users u ON u.id = l.user_id
        WHERE u.status = 'ACTIVE'
          AND l.remaining > 0 AND l.expires_at > :now AND l.expires_at <= :until
        GROUP BY l.user_id ORDER BY l.user_id
        """,
        nativeQuery = true,
    )
    fun findExpiryReminderTargetRows(@Param("now") now: Instant, @Param("until") until: Instant): List<Array<Any>>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM CreditLot l WHERE l.userId = :userId AND l.transactionId = :transactionId")
    fun findByUserIdAndTransactionIdForUpdate(userId: Long, transactionId: Long): CreditLot?

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

    /** 적립 행 → 그 적립이 만든 로트(1:1). 이용내역의 획득 행 만료일을 N+1 없이 배치 해석한다(KNK-1044). */
    fun findByTransactionIdIn(transactionIds: Collection<Long>): List<CreditLot>
}

private data class ExpiryTarget(override val userId: Long, override val expiresAt: Instant) : CreditExpiryTarget

private fun instantOf(value: Any): Instant = when (value) {
    is Instant -> value
    is OffsetDateTime -> value.toInstant()
    is java.sql.Timestamp -> value.toInstant()
    else -> error("만료 시각 타입을 알 수 없습니다: ${value.javaClass.name}")
}
