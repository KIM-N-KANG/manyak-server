package com.knk.manyak.credit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 완료보다 먼저 도착한 전체 환불. 완료 처리 시 소비한다. */
@Entity
@Table(name = "groble_refund_marks")
class GrobleRefundMark(
    @Id
    @Column(name = "merchant_uid", length = 255)
    val merchantUid: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
