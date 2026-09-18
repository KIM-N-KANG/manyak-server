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

enum class ConsentDocType { TERMS, PRIVACY, AGE14 }

data class UserConsentId(
    val userId: Long = 0,
    val docType: ConsentDocType = ConsentDocType.TERMS,
    val version: String = "",
) : Serializable

/** 문서 버전별 최초 동의 증빙. 탈퇴 후에도 보존하며 기존 행은 갱신하지 않는다. */
@Entity
@Table(name = "user_consents")
@IdClass(UserConsentId::class)
class UserConsent(
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20, updatable = false)
    val docType: ConsentDocType,
    @Id
    @Column(nullable = false, length = 20, updatable = false)
    val version: String,
    @Column(name = "agreed_at", nullable = false, updatable = false)
    val agreedAt: Instant = Instant.now(),
)
