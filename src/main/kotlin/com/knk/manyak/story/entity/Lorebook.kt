package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/**
 * 로어북(장르 공용 용어 사전). 트리거 키워드가 있는 키워드북과는 별개다.
 * 각 로어북은 이름과 본문(TEXT 통짜)을 가진 공용 카탈로그이며, 스토리는 [StoryLorebook]으로 여러 개를 참조한다.
 */
@Entity
@Table(name = "lorebooks")
class Lorebook(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(length = 50)
    val genre: String? = null,

    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,

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
