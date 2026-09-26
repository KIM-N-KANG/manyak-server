package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.*
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.StoryEditService
import com.knk.manyak.global.security.SuspensionGuard
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.util.UUID

@Service
class StorySubmissionService(
    private val submissions: StorySubmissionRepository,
    private val stories: StoryRepository,
    private val users: UserRepository,
    private val forms: SubmissionFormAssembler,
    private val edit: StoryEditService,
    private val mapper: ObjectMapper,
    private val events: ApplicationEventPublisher,
    private val suspension: SuspensionGuard,
) {
    @Transactional
    fun create(request: CreateGeneralStoryRequest, userId: Long): SubmissionAccepted {
        requireMember(userId)
        val form = forms.create(request, userId)
        return dispatch(submissions.save(StorySubmission(userId = userId, kind = SubmissionKind.CREATE,
            payload = mapper.writeValueAsString(request), inputForm = mapper.writeValueAsString(form))))
    }

    @Transactional
    fun update(storyId: String, request: UpdateStoryRequest, userId: Long): Any {
        requireMember(userId)
        val story = ownedStory(storyId, userId, true)
        requireNoPending(story.id)
        if (request.visibility != null && request.copy(visibility = null) == UpdateStoryRequest()) {
            edit.updateStory(storyId, userId, request)
            return editForm(storyId, userId)
        }
        val previous = submissions.findFirstByStoryIdAndStatusNotOrderByCreatedAtDescIdDesc(story.id, SubmissionStatus.APPROVED)
        val priorImageIds = previous?.let { mapper.readTree(it.inputForm).path("characters").toList()
            .flatMap { character -> character.path("images").toList() }
            .mapNotNull { image -> image.path("id").takeUnless { it.isNull || it.isMissingNode }?.asText() }.toSet() }.orEmpty()
        val form = forms.update(story, request, userId, allowDeletedImages = previous != null, previousImageIds = priorImageIds)
        // 재제출에서 삭제된 기존 이미지 참조는 적용 시에도 제외한다. 원문 payload는 그대로 보존한다.
        val submission = previous?.let { submissions.lockById(it.id) } ?: StorySubmission(userId = userId,
            storyId = story.id, kind = SubmissionKind.UPDATE, payload = mapper.writeValueAsString(request))
        if (previous != null) submission.resubmit(mapper.writeValueAsString(request))
        submission.inputForm = mapper.writeValueAsString(form)
        return dispatch(submissions.save(submission))
    }

    @Transactional
    fun resubmit(id: String, request: CreateGeneralStoryRequest, userId: Long): SubmissionAccepted {
        requireMember(userId)
        val row = owned(id, userId)
        val submission = submissions.lockById(row.id) ?: missing()
        if (submission.kind != SubmissionKind.CREATE || submission.status !in setOf(SubmissionStatus.REJECTED, SubmissionStatus.FAILED)) conflict()
        val form = forms.create(request, userId)
        submission.resubmit(mapper.writeValueAsString(request))
        submission.inputForm = mapper.writeValueAsString(form)
        return dispatch(submission)
    }

    @Transactional(readOnly = true)
    fun get(id: String, userId: Long): Map<String, Any?> = response(owned(id, userId))

    @Transactional(readOnly = true)
    fun list(userId: Long, limit: Int): List<Map<String, Any?>> = submissions
        .findByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(userId, SubmissionStatus.APPROVED, PageRequest.of(0, limit.coerceIn(1, 100))).map(::response)

    @Transactional(readOnly = true)
    fun editForm(storyId: String, userId: Long?): JsonNode {
        val live = edit.getEditForm(storyId, userId)
        val story = stories.findByPublicIdAndDeletedAtIsNull(uuid(storyId)) ?: missing()
        val row = submissions.findFirstByStoryIdAndStatusNotOrderByCreatedAtDescIdDesc(story.id, SubmissionStatus.APPROVED)
        val form = if (row == null) mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(live) else currentForm(row)
        form.set("submission", mapper.valueToTree(row?.let {
            SubmissionMetadata(it.publicId.toString(), it.status, forms.remap(it.issues, mapper.readTree(it.inputForm), form), it.errorCode)
        }))
        return form
    }

    @Transactional
    fun delete(id: String, userId: Long) {
        requireMember(userId)
        val row = owned(id, userId)
        row.storyId?.let { stories.findById(it).orElse(null)?.let { story -> stories.findByPublicIdAndDeletedAtIsNullForUpdate(story.publicId) } }
        val locked = submissions.lockById(row.id) ?: missing()
        if (locked.status == SubmissionStatus.APPROVED) conflict()
        submissions.delete(locked)
    }

    fun requireNoPending(storyId: Long) {
        if (submissions.existsByStoryIdAndStatus(storyId, SubmissionStatus.PENDING)) conflict()
    }

    private fun dispatch(row: StorySubmission): SubmissionAccepted {
        events.publishEvent(SubmissionRequested(row.id, row.attempt))
        return SubmissionAccepted(row.publicId.toString())
    }

    private fun response(row: StorySubmission): Map<String, Any?> {
        val form = if (row.status == SubmissionStatus.APPROVED) mapper.readTree(row.inputForm) else currentForm(row)
        return linkedMapOf("submissionId" to row.publicId.toString(), "storyId" to row.storyId?.let { stories.findById(it).orElse(null)?.publicId?.toString() },
            "kind" to row.kind, "payload" to form, "status" to row.status,
            "issues" to forms.remap(row.issues, mapper.readTree(row.inputForm), form), "errorCode" to row.errorCode,
            "createdAt" to row.createdAt, "updatedAt" to row.updatedAt, "decidedAt" to row.decidedAt)
    }

    private fun currentForm(row: StorySubmission): tools.jackson.databind.node.ObjectNode = when (row.kind) {
        SubmissionKind.CREATE -> forms.create(mapper.readValue(row.payload, CreateGeneralStoryRequest::class.java), row.userId, false)
        SubmissionKind.UPDATE -> forms.update(stories.findById(requireNotNull(row.storyId)).orElseThrow(),
            mapper.readValue(row.payload, UpdateStoryRequest::class.java), row.userId, false, true)
    }

    private fun requireMember(userId: Long) {
        val user = users.findByIdForUpdate(userId) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (user.status == UserStatus.DELETED) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        suspension.requireActive(userId)
    }
    private fun owned(id: String, userId: Long): StorySubmission = submissions.findByPublicId(uuid(id))?.takeIf { it.userId == userId } ?: missing()
    private fun ownedStory(id: String, userId: Long, lock: Boolean): Story {
        val story = (if (lock) stories.findByPublicIdAndDeletedAtIsNullForUpdate(uuid(id)) else stories.findByPublicIdAndDeletedAtIsNull(uuid(id))) ?: missing()
        if (story.userId != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        return story
    }
    private fun uuid(id: String): UUID = try { UUID.fromString(id) } catch (_: IllegalArgumentException) { missing() }
    private fun missing(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "제출본 또는 스토리를 찾을 수 없습니다.")
    private fun conflict(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, "검수 상태에서 허용되지 않는 요청입니다.")
}
