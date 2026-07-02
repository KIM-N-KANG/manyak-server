package com.knk.manyak.story.entity

import com.knk.manyak.story.dto.StorylineRating
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "story_creation_storyline_ratings",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_creation_storyline_ratings_storyline",
            columnNames = ["storyline_id"],
        ),
    ],
)
class StoryCreationStorylineRating(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 평가 대상 스토리라인(story_creation_storylines.id). 탐색이 필요 없어 원시 FK 값만 보관한다.
    @Column(name = "storyline_id", nullable = false)
    val storylineId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    var rating: StorylineRating,

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
