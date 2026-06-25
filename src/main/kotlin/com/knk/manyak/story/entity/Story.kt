package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "stories")
class Story(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 외부에 노출하는 추측 불가능한 식별자. 순차 PK 열거(IDOR)를 막기 위해 API는 이 값만 입출력한다.
    // 내부 PK(id)는 FK·조인·성능용으로만 사용한다. (채팅 KNK-178 선례)
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    @Column(name = "user_id")
    val userId: Long? = null,

    @Column(nullable = false, length = 100)
    val title: String,

    @Column(name = "one_line_intro", length = 255)
    val oneLineIntro: String? = null,

    @Column(columnDefinition = "TEXT")
    val description: String? = null,

    @Column(length = 255)
    val genre: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
