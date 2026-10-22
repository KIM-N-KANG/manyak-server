package com.knk.manyak.push.entity

import com.knk.manyak.push.dto.*
import jakarta.persistence.*
import java.time.Instant

/** 메시지별 최초 결과를 영구 보존한다. 외부에서 조회하는 개별 리소스는 없다. */
@Entity
@Table(name = "push_delivery_results")
class PushDeliveryResult(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "message_id", nullable = false, unique = true, length = 255) val messageId: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) val type: PushDeliveryType,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val kind: PushKind,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "campaign_id") val campaign: PushCampaign? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") val user: com.knk.manyak.auth.entity.User? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val outcome: PushDeliveryOutcome,
    @Column(nullable = false, length = 40) val reason: String,
    @Column(name = "delivered_devices", nullable = false) val deliveredDevices: Int,
    @Column(name = "failed_devices", nullable = false) val failedDevices: Int,
    @Column(name = "completed_at", nullable = false) val completedAt: Instant,
)
