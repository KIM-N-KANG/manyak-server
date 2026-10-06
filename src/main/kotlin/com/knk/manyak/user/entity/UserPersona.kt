package com.knk.manyak.user.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "user_personas")
class UserPersona(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "public_id", nullable = false, unique = true, updatable = false) val publicId: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false, updatable = false) val userId: Long,
    @Column(nullable = false, columnDefinition = "TEXT") var name: String,
    @Column(nullable = false, columnDefinition = "TEXT") var description: String,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
) {
    @PreUpdate fun touch() { updatedAt = Instant.now() }
}
