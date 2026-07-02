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
@Table(name = "story_creation_storyline_recommended_infos")
class StoryCreationStorylineRecommendedInfo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storyline_id", nullable = false)
    val storyline: StoryCreationStoryline,

    @Column(name = "info_text", nullable = false, columnDefinition = "TEXT")
    val infoText: String,

    @Column(name = "info_order", nullable = false)
    val infoOrder: Short,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
