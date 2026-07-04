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
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 스토리가 참조하는 로어북(다대다 조인). 한 스토리는 같은 로어북을 중복 참조할 수 없다.
 */
@Entity
@Table(
    name = "story_lorebooks",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_lorebooks_story_lorebook",
            columnNames = ["story_id", "lorebook_id"],
        ),
    ],
)
class StoryLorebook(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    val story: Story,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lorebook_id", nullable = false)
    val lorebook: Lorebook,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Short,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
