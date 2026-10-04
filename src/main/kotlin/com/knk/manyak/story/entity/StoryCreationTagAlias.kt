package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "story_creation_tag_aliases")
class StoryCreationTagAlias(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "tag_id", nullable = false)
    val tagId: Long,
    @Column(nullable = false, length = 30)
    val alias: String,
    @Column(name = "normalized_alias", nullable = false, unique = true, length = 60)
    val normalizedAlias: String = StoryCreationTag.normalize(alias),
)
