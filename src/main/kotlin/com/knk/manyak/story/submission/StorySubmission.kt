package com.knk.manyak.story.submission

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.UUID

enum class SubmissionKind { CREATE, UPDATE }
enum class SubmissionStatus { PENDING, APPROVED, REJECTED, FAILED }
data class ModerationIssue(val path: String = "", val type: String = "", val rule: String = "", val reason: String = "")

@Entity
@Table(name = "story_submissions")
class StorySubmission(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "public_id", nullable = false, unique = true) val publicId: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(name = "story_id") var storyId: Long? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val kind: SubmissionKind,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var payload: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_form", nullable = false, columnDefinition = "jsonb") var inputForm: String = "{}",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "image_copies", nullable = false, columnDefinition = "jsonb") var imageCopies: Map<String, String> = emptyMap(),
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: SubmissionStatus = SubmissionStatus.PENDING,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var issues: List<ModerationIssue> = emptyList(),
    @Column(name = "error_code") var errorCode: String? = null,
    @Column(nullable = false) var attempt: Int = 1,
    @Column(name = "dispatched_at") var dispatchedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "decided_at") var decidedAt: Instant? = null,
) {
    fun resubmit(payload: String, now: Instant = Instant.now()) {
        this.payload = payload
        imageCopies = emptyMap()
        status = SubmissionStatus.PENDING
        issues = emptyList()
        errorCode = null
        decidedAt = null
        attempt++
        dispatchedAt = null
        updatedAt = now
    }
}

interface StorySubmissionRepository : JpaRepository<StorySubmission, Long> {
    fun findByPublicId(publicId: UUID): StorySubmission?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StorySubmission s where s.id = :id")
    fun lockById(id: Long): StorySubmission?
    fun existsByStoryIdAndStatus(storyId: Long, status: SubmissionStatus): Boolean
    fun findFirstByStoryIdAndStatusNotOrderByCreatedAtDescIdDesc(storyId: Long, status: SubmissionStatus): StorySubmission?
    fun findByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(userId: Long, status: SubmissionStatus, pageable: Pageable): List<StorySubmission>
    @Modifying @Query("delete from StorySubmission s where s.storyId = :storyId")
    fun deleteForStory(storyId: Long)
    @Modifying @Query("delete from StorySubmission s where s.userId = :userId")
    fun deleteForUser(userId: Long)
}

data class SubmissionAccepted(val submissionId: String, val status: SubmissionStatus = SubmissionStatus.PENDING)
data class SubmissionMetadata(val submissionId: String, val status: SubmissionStatus, val issues: List<ModerationIssue>, val errorCode: String?)
data class SubmissionRequested(val id: Long, val attempt: Int)
data class StoryModerationCompleted(val userId: Long, val submissionId: String, val storyId: String?, val status: SubmissionStatus, val attempt: Int)
