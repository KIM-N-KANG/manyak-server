package com.knk.manyak.story.home

import com.knk.manyak.story.service.StoryListSort
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class HomeSectionSource { MANUAL, FILTER }

@Embeddable
data class HomeSectionStory(
    @Column(name = "story_id", nullable = false) val storyId: Long = 0,
    @Column(name = "sort_order", nullable = false) val sortOrder: Int = 1,
)

@Entity
@Table(name = "home_sections")
class HomeSection(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "public_id", nullable = false, unique = true, updatable = false) val publicId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 30) var name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) var source: HomeSectionSource,
    @Enumerated(EnumType.STRING) @Column(name = "sort_by", length = 10) var sortBy: StoryListSort? = null,
    @Column(name = "original_only", nullable = false) var originalOnly: Boolean = false,
    @Column(name = "item_limit", nullable = false) var itemLimit: Int = 20,
    @Column(name = "is_active", nullable = false) var isActive: Boolean = true,
    @Column(name = "sort_order", nullable = false) var sortOrder: Int,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @ElementCollection
    @CollectionTable(name = "home_section_genres", joinColumns = [JoinColumn(name = "section_id")])
    @Column(name = "genre", nullable = false, length = 30)
    var genres: MutableSet<String> = linkedSetOf(),
    @ElementCollection
    @CollectionTable(name = "home_section_stories", joinColumns = [JoinColumn(name = "section_id")],
        uniqueConstraints = [UniqueConstraint(columnNames = ["section_id", "sort_order"])])
    var stories: MutableSet<HomeSectionStory> = linkedSetOf(),
)
