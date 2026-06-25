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
@Table(name = "story_creation_example_recommended_infos")
class StoryCreationExampleRecommendedInfo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "example_id", nullable = false)
    val example: StoryCreationExample,

    @Column(name = "info_text", nullable = false, columnDefinition = "TEXT")
    val infoText: String,

    @Column(name = "info_order", nullable = false)
    val infoOrder: Short,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
