package com.knk.manyak.story.entity

import com.knk.manyak.story.dto.SimpleStoryCharacterGender
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

enum class StoryCreationCharacterRole {
    PROTAGONIST,
    SUPPORTING_CHARACTER,
}

@Entity
@Table(name = "story_creation_characters")
class StoryCreationCharacter(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creation_session_id", nullable = false)
    val creationSession: StoryCreationSession,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val role: StoryCreationCharacterRole,

    @Column(length = 30)
    val name: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    val gender: SimpleStoryCharacterGender? = null,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Short,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
