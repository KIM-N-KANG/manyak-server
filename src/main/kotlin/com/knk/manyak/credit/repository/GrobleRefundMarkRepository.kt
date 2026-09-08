package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.GrobleRefundMark
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface GrobleRefundMarkRepository : JpaRepository<GrobleRefundMark, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM GrobleRefundMark m WHERE m.merchantUid = :merchantUid")
    fun findByIdForUpdate(merchantUid: String): GrobleRefundMark?

    /** 유니크 충돌을 예외로 처리하면 바깥 트랜잭션도 실패하므로 DB에서 중복을 무시한다. */
    @Modifying
    @Query(value = "INSERT INTO groble_refund_marks (merchant_uid, created_at) VALUES (:merchantUid, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING", nativeQuery = true)
    fun insertIfAbsent(merchantUid: String): Int
}
