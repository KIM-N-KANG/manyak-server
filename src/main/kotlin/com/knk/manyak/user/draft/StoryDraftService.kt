package com.knk.manyak.user.draft

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.error.*
import com.knk.manyak.global.security.requireActiveStatus
import com.knk.manyak.image.service.UploadedImageKind
import com.knk.manyak.story.entity.StoryCreationStage
import com.knk.manyak.story.entity.StoryCreationRequestStatus
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import com.knk.manyak.story.service.StoryImageAccess
import com.knk.manyak.story.service.SimpleStoryCreationService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Service
class StoryDraftService(
    private val drafts: StoryDraftRepository,
    private val users: UserRepository,
    private val requests: StoryCreationRequestRepository,
    private val images: StoryImageAccess,
    private val generation: SimpleStoryCreationService,
    private val mapper: ObjectMapper,
    private val entityManager: jakarta.persistence.EntityManager,
    @param:Value("\${manyak.story.pending-reclaim-after-seconds:300}") private val reclaimAfterSeconds: Long,
) {
    @Transactional(readOnly = true)
    fun list(userId: Long): List<Map<String, Any?>> {
        val user = member(userId)
        val cutoff = Instant.now().minusSeconds(reclaimAfterSeconds)
        val cards = drafts.findByUserId(userId).map { summary(it, user).apply { put("kind", "DRAFT") } } +
            requests.findByUserIdAndStatus(userId, StoryCreationRequestStatus.PENDING)
                .filter { it.updatedAt.isAfter(cutoff) }
                .map { linkedMapOf<String, Any?>("kind" to "CREATION_REQUEST", "requestId" to it.requestId, "stage" to it.stage, "createdAt" to it.createdAt) }
        return cards.sortedWith(compareByDescending<Map<String, Any?>> { it["createdAt"] as Instant }
            .thenBy { (it["draftId"] ?: it["requestId"]).toString() })
    }

    @Transactional(readOnly = true)
    fun get(userId: Long, id: String): Map<String, Any?> {
        val user = member(userId)
        val draft = drafts.findById(uuid(id)).orElse(null)?.takeIf { it.userId == userId } ?: missing()
        val payload = mapper.readTree(draft.payload)
        val urls = keys(payload).associateWith { images.draftReadUrl(user.publicId, kind(it), it) }
        return summary(draft, user).apply {
            put("schemaVersion", draft.schemaVersion)
            put("payload", payload)
            put("generationRequestId", draft.generationRequestId)
            put("imageUrls", urls)
            put("generation", draft.generationRequestId?.takeIf { draft.stage == StoryDraftStage.STORY_DRAFT }
                ?.let { generation.getCreationRequest(it, userId) })
        }
    }

    @Transactional
    fun put(userId: Long, id: String, request: PutStoryDraftRequest): StoryDraftSaved {
        // 모든 초안 쓰기는 users → story_drafts 순서. 신규 개수 검사와 같은 ID의 버전 판정을 함께 직렬화한다.
        val user = member(userId, lock = true)
        val draftId = uuid(id)
        val old = drafts.findById(draftId).orElse(null)
        if (old != null && old.userId != userId) missing()
        if ((request.mode == StoryDraftMode.GENERAL) != (request.stage == StoryDraftStage.GENERAL_DRAFT)) bad("제작 방식과 초안 단계가 맞지 않습니다.")
        if (!request.payload.isObject) bad("payload는 JSON 객체여야 합니다.")
        val payload = mapper.writeValueAsString(request.payload)
        if (payload.toByteArray(Charsets.UTF_8).size > 65_536) throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, ApiErrorCodes.DRAFT_PAYLOAD_TOO_LARGE, "초안 입력은 64KB를 넘을 수 없습니다.")
        request.thumbnailObjectKey?.let { images.requireDraftPrefix(user.publicId, UploadedImageKind.COVER, it) }
        keys(request.payload).forEach { images.requireDraftPrefix(user.publicId, kind(it), it) }
        request.generationRequestId?.let { id ->
            val row = requests.findByRequestId(id)
            if (row == null || row.userId != userId || row.stage != StoryCreationStage.STORYLINE_GENERATION) bad("내 스토리라인 생성 요청만 연결할 수 있습니다.")
        }
        if (old != null) {
            if (old.mode == request.mode && old.stage == request.stage && old.schemaVersion == request.schemaVersion &&
                mapper.readTree(old.payload) == request.payload && old.title == request.title && old.oneLineIntro == request.oneLineIntro &&
                old.thumbnailObjectKey == request.thumbnailObjectKey && old.generationRequestId == request.generationRequestId) return saved(old)
            if (request.baseVersion != old.version) throw CodedResponseStatusException(HttpStatus.CONFLICT, ApiErrorCodes.DRAFT_VERSION_CONFLICT, "초안이 다른 곳에서 변경되었습니다.",
                details = listOf(ApiErrorDetail("version", old.version.toString()), ApiErrorDetail("updatedAt", old.updatedAt.toString())))
            old.mode = request.mode; old.stage = request.stage; old.schemaVersion = request.schemaVersion; old.payload = payload
            old.title = request.title; old.oneLineIntro = request.oneLineIntro; old.thumbnailObjectKey = request.thumbnailObjectKey
            old.generationRequestId = request.generationRequestId; old.version++; old.updatedAt = Instant.now()
            return saved(old)
        }
        if (request.baseVersion != null) bad("새 초안의 baseVersion은 null이어야 합니다.")
        if (drafts.countByUserId(userId) >= 5) throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, ApiErrorCodes.DRAFT_LIMIT_EXCEEDED, "초안은 최대 5개까지 저장할 수 있습니다.")
        val created = StoryDraft(draftId, userId, request.mode, request.stage, request.schemaVersion, payload,
            request.title, request.oneLineIntro, request.thumbnailObjectKey, request.generationRequestId)
        // 할당 UUID의 save(merge)는 다른 회원의 동시 신규 행을 덮어쓸 수 있으므로 INSERT만 허용한다.
        try {
            entityManager.persist(created)
            entityManager.flush()
        } catch (e: org.hibernate.exception.ConstraintViolationException) {
            if (e.sqlState == "23505") missing() // 다른 회원이 같은 UUID를 먼저 삽입했다.
            throw e
        }
        return saved(created)
    }

    @Transactional
    fun delete(userId: Long, id: String) {
        member(userId, lock = true)
        val parsed = StoryImageAccess.parsePublicIdOrNull(id) ?: return
        drafts.deleteOwned(parsed, userId)
    }

    private fun member(id: Long, lock: Boolean = false): User {
        val user = (if (lock) users.findByIdForUpdate(id) else users.findById(id).orElse(null))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        requireActiveStatus(user.status)
        return user
    }
    private fun summary(d: StoryDraft, u: User) = linkedMapOf<String, Any?>(
        "draftId" to d.id, "mode" to d.mode, "stage" to d.stage, "title" to d.title, "oneLineIntro" to d.oneLineIntro,
        "thumbnailUrl" to d.thumbnailObjectKey?.let { images.draftReadUrl(u.publicId, UploadedImageKind.COVER, it) },
        "version" to d.version, "createdAt" to d.createdAt, "updatedAt" to d.updatedAt,
    )
    private fun saved(d: StoryDraft) = StoryDraftSaved(d.id, d.version, d.createdAt, d.updatedAt)
    private fun uuid(id: String) = StoryImageAccess.parsePublicIdOrNull(id) ?: missing()
    private fun missing(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "초안을 찾을 수 없습니다.")
    private fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
    private fun kind(key: String) = if (key.startsWith("thumbnails/uploaded/")) UploadedImageKind.COVER else UploadedImageKind.CHARACTER
    private fun keys(node: JsonNode): Set<String> {
        val result = linkedSetOf<String>()
        fun visit(value: JsonNode) {
            if (value.isString) {
                val key = value.asText()
                if (key.startsWith("thumbnails/uploaded/") || key.startsWith("characters/uploaded/")) result.add(key)
            } else if (value.isObject || value.isArray) value.forEach { visit(it) }
        }
        visit(node)
        return result
    }
}
