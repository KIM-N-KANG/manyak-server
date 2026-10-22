package com.knk.manyak.admin.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "admin_accounts")
class AdminAccount(
    @Id @Column(name = "user_id") val userId: Long,
    @Column(name = "granted_at", nullable = false) val grantedAt: Instant = Instant.now(),
)
