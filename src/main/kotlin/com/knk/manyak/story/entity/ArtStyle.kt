package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "art_styles")
class ArtStyle(
    @Id
    @Column(nullable = false, length = 50)
    val code: String,

    @Column(nullable = false, length = 30)
    val name: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val description: String,

    @Column(name = "preview_image_url", columnDefinition = "TEXT")
    val previewImageUrl: String? = null,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
