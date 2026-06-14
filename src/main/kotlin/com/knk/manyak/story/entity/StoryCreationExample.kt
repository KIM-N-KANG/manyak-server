package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "story_creation_examples")
class StoryCreationExample(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creation_session_id", nullable = false)
    val creationSession: StoryCreationSession,

    @Column(name = "example_text", nullable = false, columnDefinition = "TEXT")
    val exampleText: String,

    @Column(name = "example_order", nullable = false)
    val exampleOrder: Short,

    @Column(name = "is_selected", nullable = false)
    val isSelected: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
