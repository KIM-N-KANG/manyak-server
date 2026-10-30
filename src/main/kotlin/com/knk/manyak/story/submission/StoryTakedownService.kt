package com.knk.manyak.story.submission

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.inbox.DomainNotificationRecorder
import com.knk.manyak.story.entity.StoryTakedownReason
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.OfficialStoryAccount
import com.knk.manyak.story.service.StoryTakedownStateService
import com.knk.manyak.story.service.UsernameTokenRenderer
import jakarta.persistence.EntityManager
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class StoryTakedownRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val reasonCode: StoryTakedownReason,
    @field:Schema(minLength = 1, maxLength = 100, pattern = "(?sU).*\\S.*", description = "ETC일 때 필수이며 다른 사유에서는 생략 또는 null")
    val reasonDetail: String? = null,
    @field:Schema(minLength = 1, maxLength = 500, pattern = "(?sU).*\\S.*")
    val memo: String? = null,
) {
    fun validate() {
        val invalidMemo = memo != null && (memo.isBlank() || memo.length > 500)
        val invalidDetail = if (reasonCode == StoryTakedownReason.ETC)
            reasonDetail.isNullOrBlank() || reasonDetail.length > 100 else reasonDetail != null
        if (invalidMemo || invalidDetail) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "공개 중단 사유와 메모가 올바르지 않습니다.")
        }
    }
}
data class StoryTakedownResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val storyId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = ["PRIVATE"]) val visibility: StoryVisibility,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val takedownReason: StoryTakedownReason,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val takedownReasonDetail: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val takenDownAt: Instant,
)
data class StoryTakenDown(val userId: Long, val storyId: UUID, val title: String, val reasonCode: StoryTakedownReason,
    val reasonDetail: String?, val takenDownAt: Instant) {
    val messageId: String get() = "story-taken-down:$storyId:${takenDownAt.toEpochMilli()}"
    val reason: String get() = if (reasonCode == StoryTakedownReason.ETC) requireNotNull(reasonDetail) else reasonCode.displayName
}
data class StoryTakenDownRecorded(val storyId: UUID, val reasonCode: StoryTakedownReason, val previousVisibility: StoryVisibility,
    val failedSubmissionId: UUID?, val notified: Boolean)
data class StoryTakedownCleared(val storyId: UUID, val submissionId: UUID, val visibility: StoryVisibility)

@Service
class StoryTakedownService(
    private val stories: StoryRepository,
    private val users: UserRepository,
    private val submissions: StorySubmissionRepository,
    private val official: OfficialStoryAccount,
    private val state: StoryTakedownStateService,
    private val notifications: DomainNotificationRecorder,
    private val audits: AdminAuditService,
    private val mapper: ObjectMapper,
    private val events: ApplicationEventPublisher,
    private val entityManager: EntityManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun takeDown(adminUserId: Long, storyId: UUID, request: StoryTakedownRequest): StoryTakedownResponse {
        request.validate()
        val candidate = stories.findByPublicId(storyId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        val ownerId = candidate.userId ?: conflict()
        entityManager.detach(candidate)
        // 결과 반영·소유자 수정·탈퇴와 같은 순서. 대상 상태는 잠금을 얻은 뒤 다시 읽는다.
        val owner = users.findByIdForUpdate(ownerId) ?: conflict()
        val story = stories.findByPublicIdForUpdate(storyId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        if (story.deletedAt != null || story.takenDownAt != null || story.userId != owner.id || owner.id == official.officialUserId()) conflict()
        val previous = story.visibility
        val now = clock.instant()
        state.apply(story, request.reasonCode, request.reasonDetail, now)
        val pending = submissions.lockPendingForStory(story.id)
        pending.forEach {
            it.status = SubmissionStatus.FAILED
            it.errorCode = "STORY_TAKEN_DOWN"
            it.decidedAt = now
            it.updatedAt = now
            it.retryCount = 0
            it.nextAttemptAt = null
            it.heldAt = null
            it.holdReason = null
            it.dispatchedAt = null
        }
        val notified = owner.status != UserStatus.DELETED
        val event = StoryTakenDown(owner.id, story.publicId, UsernameTokenRenderer.render(story.title, story.protagonistName),
            request.reasonCode, request.reasonDetail, now)
        if (notified) {
            notifications.takenDown(event)
            events.publishEvent(event)
        }
        // 감사는 마지막 DB 작업이며 처리자의 권한도 재검사한다. 실패하면 위 변경 전체가 롤백된다.
        audits.recordChange(adminUserId, AdminAuditAction.STORY_TAKEN_DOWN, story.publicId.toString(), event.reason,
            mapper.valueToTree(mapOf("visibility" to previous)), mapper.valueToTree(mapOf(
                "visibility" to story.visibility, "takedownReason" to request.reasonCode,
                "takedownReasonDetail" to request.reasonDetail, "takenDownAt" to now, "memo" to request.memo)))
        events.publishEvent(StoryTakenDownRecorded(story.publicId, request.reasonCode, previous, pending.firstOrNull()?.publicId, notified))
        return StoryTakedownResponse(story.publicId, story.visibility, request.reasonCode, request.reasonDetail, now)
    }
    private fun conflict(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, "공개 중단할 수 없는 스토리입니다.")
}
