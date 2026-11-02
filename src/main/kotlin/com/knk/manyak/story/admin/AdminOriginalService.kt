package com.knk.manyak.story.admin

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.image.service.ImageUrlResolver
import com.knk.manyak.story.dto.*
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.*
import jakarta.validation.Validator
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/** 관리자 인증은 필터, 소유 범위는 공식 계정, 저장과 이미지·스냅샷 적용은 기존 STORY 서비스가 맡는다. */
@Service
class AdminOriginalService(
    private val official: OfficialStoryAccount,
    private val users: UserRepository,
    private val stories: StoryRepository,
    private val creation: GeneralStoryCreationService,
    private val edit: StoryEditService,
    private val images: StoryImageService,
    private val originalImages: OriginalImages,
    private val genres: GenreInputValidator,
    private val audits: AdminAuditService,
    private val mapper: ObjectMapper,
    private val validator: Validator,
    private val jdbc: JdbcTemplate,
    private val imageUrls: ImageUrlResolver,
) {
    @Transactional(readOnly = true)
    fun detail(id: UUID): ObjectNode = response(owned(id))

    @Transactional
    fun create(body: ObjectNode, actor: Long): ObjectNode {
        val reason = reason(body.path("reason").takeIf { it.isString }?.asText())
        if (body.has("draftId")) bad("draftId는 오리지널 등록에 사용할 수 없습니다.")
        val owner = ownerOrUnavailable()
        val status = status(body) ?: StoryStatus.DRAFT
        val fields = fields(body)
        val story = if (status == StoryStatus.PUBLISHED) {
            val input = read(fields, CreateGeneralStoryRequest::class.java)
            validate(input)
            val normalized = input.copy(genres = genres.normalize(input.genres))
            val created = creation.createGeneralStory(normalized, owner, originalImages.prepare(owner, null, input.thumbnailObjectKey, input.characters))
            stories.findByPublicIdAndDeletedAtIsNull(UUID.fromString(created.id))!!
        } else {
            val input = read(fields, UpdateStoryRequest::class.java)
            validateDraft(input, creating = true)
            if (input.visibility == StoryVisibility.PUBLIC) bad("초안은 비공개여야 합니다.")
            val normalized = normalize(input)
            val draft = creation.createOriginalDraft(requireNotNull(normalized.title), owner)
            edit.updateStory(draft.publicId.toString(), owner, normalized, originalImages.prepare(owner, null, input.thumbnailObjectKey, input.characters))
            draft
        }
        stories.flush()
        val result = response(story)
        audits.recordChange(actor, AdminAuditAction.ORIGINAL_CREATED, story.publicId.toString(), reason, after = state(story))
        return result
    }

    @Transactional
    fun update(id: UUID, body: ObjectNode, actor: Long): ObjectNode {
        val story = owned(id, lock = true)
        val reason = reason(body.path("reason").takeIf { it.isString }?.asText())
        val before = state(story)
        val next = status(body) ?: story.status
        if (story.status == StoryStatus.PUBLISHED && next == StoryStatus.DRAFT) bad("발행한 스토리는 초안으로 되돌릴 수 없습니다.")
        val input = read(fields(body), UpdateStoryRequest::class.java)
        if (story.status == StoryStatus.DRAFT && next == StoryStatus.DRAFT) validateDraft(input)
        else validate(input)
        if (next == StoryStatus.DRAFT && (input.visibility ?: story.visibility) == StoryVisibility.PUBLIC) bad("초안은 비공개여야 합니다.")
        val normalized = normalize(input, story.genre)
        val publishing = story.status == StoryStatus.DRAFT && next == StoryStatus.PUBLISHED
        // 같은 스토리 쓰기 잠금을 유지한 채 적용한다. 발행 검증 실패 시 자식과 스냅샷도 함께 롤백된다.
        story.status = next
        edit.updateStory(id.toString(), story.userId, normalized, originalImages.prepare(requireNotNull(story.userId), story, input.thumbnailObjectKey, input.characters))
        if (publishing) validatePublished(story)
        story.updatedAt = java.time.Instant.now()
        stories.flush()
        val result = response(story)
        audits.recordChange(actor, AdminAuditAction.ORIGINAL_UPDATED, id.toString(), reason, before, state(story))
        return result
    }

    @Transactional
    fun deleteThumbnail(id: UUID, rawReason: String, actor: Long): ObjectNode {
        val story = owned(id, lock = true)
        val reason = reason(rawReason)
        val before = mapper.createObjectNode().put("thumbnailUrl", imageUrls.thumbnailUrlFor(story.thumbnailImageUrl, story.thumbnailImageKey))
        images.deleteOriginalThumbnail(id.toString(), requireNotNull(story.userId))
        stories.flush()
        val result = response(story)
        audits.recordChange(actor, AdminAuditAction.ORIGINAL_THUMBNAIL_DELETED, id.toString(), reason, before = before)
        return result
    }

    @Transactional(readOnly = true)
    fun presign(id: UUID?, input: ImagePresignRequest): ImagePresignResponse {
        val owner = if (id == null) ownerOrUnavailable() else requireNotNull(owned(id).userId)
        return try {
            if (id == null) images.presignDraft(owner, input) else images.presign(id.toString(), owner, input)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소를 사용할 수 없습니다.", e)
        }
    }

    /** 발행 시 현재 전체 결과를 일반 등록 DTO로 되돌려 동일한 선언적 검증을 적용한다. 이미지 id는 유지 항목이다. */
    private fun validatePublished(story: Story) {
        val form = mapper.valueToTree<ObjectNode>(edit.getEditForm(story.publicId.toString(), story.userId))
        val input = read(form, CreateGeneralStoryRequest::class.java)
        validate(input)
    }

    private fun normalize(input: UpdateStoryRequest, existing: String? = null) = input.copy(
        protagonistName = normalizeProtagonistName(input.protagonistName),
        genres = input.genres?.let { if (it.isEmpty()) it else genres.normalize(it, existing) },
    )

    // 초안은 빈 배열을 저장할 수 있다. 값이 있는 필드는 일반 수정의 제약을 적용한다.
    private fun validateDraft(input: UpdateStoryRequest, creating: Boolean = false) {
        if (creating && input.title.isNullOrBlank()) bad("제목은 필수입니다.")
        validate(input.copy(genres = input.genres?.takeIf { it.isNotEmpty() }, startSettings = input.startSettings?.takeIf { it.isNotEmpty() }))
    }

    private fun fields(body: ObjectNode): ObjectNode = body.deepCopy().also { it.remove(listOf("reason", "status")) }
    private fun status(body: ObjectNode): StoryStatus? {
        val value = body.get("status") ?: return null
        if (!value.isString) bad("status는 DRAFT 또는 PUBLISHED여야 합니다.")
        return runCatching { StoryStatus.valueOf(value.asText()) }.getOrElse { bad("status는 DRAFT 또는 PUBLISHED여야 합니다.") }
    }
    private fun reason(value: String?): String = value?.takeIf { it.isNotBlank() && it.length <= 500 } ?: bad("사유는 1~500자여야 합니다.")
    private fun validate(value: Any) {
        val violations = validator.validate(value)
        if (violations.isNotEmpty()) throw com.knk.manyak.global.error.CodedResponseStatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 값이 올바르지 않습니다.",
            details = violations.map { com.knk.manyak.global.error.ApiErrorDetail(it.propertyPath.toString(), it.message) })
    }
    private fun <T> read(body: ObjectNode, type: Class<T>): T = try { mapper.treeToValue(body, type) }
        catch (_: tools.jackson.core.JacksonException) { bad("요청 값이 올바르지 않습니다.") }
        catch (_: IllegalArgumentException) { bad("요청 값이 올바르지 않습니다.") }

    private fun ownerOrUnavailable(): Long = official.officialUserId()
        ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "공식 계정이 설정되지 않았습니다.")
    private fun owned(id: UUID, lock: Boolean = false): Story {
        val story = (if (lock) stories.findByPublicIdAndDeletedAtIsNullForUpdate(id) else stories.findByPublicIdAndDeletedAtIsNull(id))
            ?: missing()
        if (story.userId == null || story.userId != official.officialUserId()) missing()
        return story
    }
    private fun state(story: Story) = mapper.createObjectNode().put("status", story.status.name).put("visibility", story.visibility.name)
        .put("title", story.title).also { it.set("genres", mapper.valueToTree(genreNames(story))) }
    private fun response(story: Story): ObjectNode = summary(story).also { result ->
        val form = mapper.valueToTree<ObjectNode>(edit.getEditForm(story.publicId.toString(), story.userId))
        form.remove("submission")
        form.properties().forEach { (key, value) -> result.set(key, value) }
    }
    private fun summary(story: Story, chatCount: Long? = null, totalTurns: Long? = null): ObjectNode {
        val owner = users.findById(requireNotNull(story.userId)).orElseThrow()
        val counts = if (chatCount == null) jdbc.queryForMap("SELECT COUNT(*) AS chats, COALESCE(SUM(current_turn), 0) AS turns FROM story_chats WHERE story_id = ? AND deleted_at IS NULL", story.id) else null
        return mapper.createObjectNode().put("storyId", story.publicId.toString()).put("title", story.title)
            .put("status", story.status.name).put("visibility", story.visibility.name)
            .put("thumbnailUrl", imageUrls.thumbnailUrlFor(story.thumbnailImageUrl, story.thumbnailImageKey)).put("updatedAt", story.updatedAt.toString())
            .put("chatCount", chatCount ?: (counts!!["chats"] as Number).toLong()).put("totalTurns", totalTurns ?: (counts!!["turns"] as Number).toLong())
            .also { it.set("genres", mapper.valueToTree(genreNames(story))); it.set("owner", mapper.createObjectNode().put("memberId", owner.publicId.toString()).put("nickname", owner.nickname)) }
    }
    private fun genreNames(story: Story) = story.genre.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    @Transactional(readOnly = true)
    fun list(query: Map<String, String>): Map<String, Any> {
        if (query.containsKey("cursor") || query.containsKey("limit")) bad("cursor와 limit는 지원하지 않습니다.")
        fun number(key: String, default: Int) = query[key]?.let { it.toIntOrNull() ?: bad("페이지 값이 올바르지 않습니다.") } ?: default
        val page = number("page", 1); val size = number("size", 20)
        if (page < 1 || size !in 1..100) bad("페이지 범위가 올바르지 않습니다.")
        val sort = when (query["sort"] ?: "updatedAt") { "updatedAt" -> "s.updated_at"; "chatCount" -> "COALESCE(c.chats,0)"; "totalTurns" -> "COALESCE(c.turns,0)"; else -> bad("정렬 열이 올바르지 않습니다.") }
        val order = query["order"] ?: "desc"
        if (order !in setOf("asc", "desc")) bad("정렬 방향이 올바르지 않습니다.")
        val q = query["q"]?.trim()?.replace(Regex("\\s+"), " ")?.also { if (it.length !in 1..50) bad("검색어는 1~50자여야 합니다.") }
        val status = query["status"]?.also { if (it !in StoryStatus.entries.map { e -> e.name }) bad("status가 올바르지 않습니다.") }
        val visibility = query["visibility"]?.also { if (it !in StoryVisibility.entries.map { e -> e.name }) bad("visibility가 올바르지 않습니다.") }
        val owner = official.officialUserId()
        val args = mutableListOf<Any>(owner ?: -1L)
        val where = StringBuilder("s.user_id = ? AND s.deleted_at IS NULL")
        status?.let { where.append(" AND s.status = ?"); args.add(it) }
        visibility?.let { where.append(" AND s.visibility = ?"); args.add(it) }
        q?.let { where.append(" AND (LOWER(s.title) LIKE ? ESCAPE '!' OR CAST(s.public_id AS VARCHAR) = ?)")
            args.add("%" + it.lowercase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"); args.add(it.lowercase()) }
        val total = jdbc.queryForObject("SELECT COUNT(*) FROM stories s WHERE $where", Long::class.java, *args.toTypedArray())!!
        val rows = jdbc.query("""SELECT s.id, COALESCE(c.chats,0) AS chats, COALESCE(c.turns,0) AS turns FROM stories s
            LEFT JOIN (SELECT story_id, COUNT(*) AS chats, SUM(current_turn) AS turns FROM story_chats WHERE deleted_at IS NULL GROUP BY story_id) c ON c.story_id = s.id
            WHERE $where ORDER BY $sort $order NULLS LAST, s.public_id ASC LIMIT ? OFFSET ?""", { rs, _ -> Triple(rs.getLong("id"), rs.getLong("chats"), rs.getLong("turns")) },
            *(args + listOf(size, (page.toLong() - 1) * size)).toTypedArray())
        val byId = stories.findAllById(rows.map { it.first }).associateBy { it.id }
        return linkedMapOf("items" to rows.map { summary(byId.getValue(it.first), it.second, it.third) }, "page" to page, "size" to size,
            "totalElements" to total, "totalPages" to (total / size + if (total % size == 0L) 0 else 1))
    }
    private fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
    private fun missing(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
}
