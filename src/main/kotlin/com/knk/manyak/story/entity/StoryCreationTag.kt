package com.knk.manyak.story.entity

import com.knk.manyak.story.dto.SimpleStoryTagCategory
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

enum class StoryCreationTagSource {
    PREDEFINED,
    CUSTOM,
}

@Entity
@Table(
    name = "story_creation_tags",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_creation_tags_source_type_name",
            columnNames = ["tag_source", "tag_type", "name"],
        ),
    ],
)
class StoryCreationTag(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_type", nullable = false, length = 50)
    val tagType: SimpleStoryTagCategory,

    @Column(nullable = false, length = 30)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_source", nullable = false, length = 20)
    val tagSource: StoryCreationTagSource,

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
