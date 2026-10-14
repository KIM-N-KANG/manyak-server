package com.knk.manyak.user.draft

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class StoryDraftMode { SIMPLE, GENERAL }
enum class StoryDraftStage { GENERAL_DRAFT, KEYWORD_DRAFT, STORY_DRAFT }

@Entity
@Table(name = "story_drafts")
class StoryDraft(
    @Id val id: UUID,
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var mode: StoryDraftMode,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var stage: StoryDraftStage,
    @Column(name = "schema_version", nullable = false) var schemaVersion: Int,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var payload: String,
    @Column(columnDefinition = "TEXT") var title: String? = null,
    @Column(name = "one_line_intro", columnDefinition = "TEXT") var oneLineIntro: String? = null,
    @Column(name = "thumbnail_object_key", columnDefinition = "TEXT") var thumbnailObjectKey: String? = null,
    @Column(name = "generation_request_id") var generationRequestId: UUID? = null,
    @Column(nullable = false) var version: Int = 1,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = createdAt,
)

interface StoryDraftRepository : JpaRepository<StoryDraft, UUID> {
    fun findByUserId(userId: Long): List<StoryDraft>
    fun countByUserId(userId: Long): Long
    @Modifying
    @Query("delete from StoryDraft d where d.id = :id and d.userId = :userId")
    fun deleteOwned(id: UUID, userId: Long): Int
}

data class PutStoryDraftRequest(
    val mode: StoryDraftMode,
    val stage: StoryDraftStage,
    val schemaVersion: Int,
    val payload: JsonNode,
    val baseVersion: Int? = null,
    val title: String? = null,
    val oneLineIntro: String? = null,
    val thumbnailObjectKey: String? = null,
    val generationRequestId: UUID? = null,
)

data class StoryDraftSaved(val draftId: UUID, val version: Int, val createdAt: Instant, val updatedAt: Instant)
