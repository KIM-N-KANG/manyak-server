package com.knk.manyak.user.consent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

enum class GuestConsentDocType { GUEST_PRIVACY }

data class GuestConsentId(
    val deviceIdHash: String = "",
    val docType: GuestConsentDocType = GuestConsentDocType.GUEST_PRIVACY,
    val version: String = "",
) : Serializable

/** 문서 버전별 최초 동의 증빙. 탈퇴 후에도 보존하며 기존 행은 갱신하지 않는다. */
@Entity
@Table(name = "guest_consents")
@IdClass(GuestConsentId::class)
class GuestConsent(
    @Id
    @Column(name = "device_id_hash", nullable = false, length = 64, updatable = false)
    val deviceIdHash: String,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20, updatable = false)
    val docType: GuestConsentDocType,
    @Id
    @Column(nullable = false, length = 20, updatable = false)
    val version: String,
    @Column(name = "agreed_at", nullable = false, updatable = false)
    val agreedAt: Instant = Instant.now(),
)
