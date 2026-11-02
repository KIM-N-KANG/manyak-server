package com.knk.manyak.story.home

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.global.error.ApiErrorDetail
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

@Service
class HomeSectionService(
    private val sections: HomeSectionRepository,
    private val stories: StoryRepository,
    private val selector: HomeStorySelector,
    private val cards: StoryService,
    private val official: OfficialStoryAccount,
    private val genres: GenreInputValidator,
    private val audits: AdminAuditService,
    private val mapper: ObjectMapper,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun home(): Map<String, Any> {
        val active = sections.findByIsActiveTrueOrderBySortOrderAscCreatedAtAscIdAsc()
        val officialId = official.officialUserId()
        data class Selection(val id: String?, val name: String, val ids: List<Long>, val limit: Int)
        val selections = if (active.isEmpty()) listOf(
            Selection(null, "인기 스토리", selector.select(StoryListSort.POPULAR, emptyList(), false, officialId, 20), 20),
            Selection(null, "오리지널 스토리", selector.select(StoryListSort.POPULAR, emptyList(), true, officialId, 20), 20),
        ) else active.map { section ->
            val ids = if (section.source == HomeSectionSource.MANUAL) section.stories.sortedBy { it.sortOrder }.map { it.storyId }
                else selector.select(requireNotNull(section.sortBy), section.genres, section.originalOnly, officialId, section.itemLimit)
            Selection(section.publicId.toString(), section.name, ids, section.itemLimit)
        }
        val byId = stories.findAllById(selections.flatMap { it.ids }.toSet()).filter(::listed).associateBy { it.id }
        val summaries = cards.assembleSummaryCards(byId.values.toList()).associateBy { it.id }
        return mapOf("sections" to selections.map { selection ->
            mapOf("id" to selection.id, "name" to selection.name,
                "items" to selection.ids.mapNotNull { byId[it]?.publicId?.toString()?.let(summaries::get) }.take(selection.limit))
        })
    }

    @Transactional(readOnly = true)
    fun list(): Map<String, Any> {
        val all = sections.findAllByOrderBySortOrderAscCreatedAtAscIdAsc()
        val byId = storyMap(all)
        return mapOf("items" to all.map { response(it, byId) })
    }

    @Transactional
    fun create(body: ObjectNode, actor: Long): ObjectNode {
        val reason = reason(body)
        val section = HomeSection(name = string(body, "name", 30), source = source(body),
            sortOrder = integer(body, "sortOrder", 1, Int.MAX_VALUE) ?: nextOrder())
        apply(section, body, creating = true)
        sections.saveAndFlush(section)
        val byId = storyMap(listOf(section))
        val result = response(section, byId)
        audits.recordChange(actor, AdminAuditAction.HOME_SECTION_CREATED, section.publicId.toString(), reason, after = state(section, byId))
        return result
    }

    @Transactional
    fun update(id: UUID, body: ObjectNode, actor: Long): ObjectNode {
        val section = owned(id)
        val reason = reason(body)
        val before = state(section, storyMap(listOf(section)))
        apply(section, body, creating = false)
        section.updatedAt = Instant.now()
        sections.flush()
        val byId = storyMap(listOf(section))
        val result = response(section, byId)
        audits.recordChange(actor, AdminAuditAction.HOME_SECTION_UPDATED, id.toString(), reason, before, state(section, byId))
        return result
    }

    @Transactional
    fun delete(id: UUID, body: ObjectNode, actor: Long) {
        val section = owned(id)
        val reason = reason(body)
        val before = state(section, storyMap(listOf(section)))
        sections.delete(section)
        sections.flush()
        audits.recordChange(actor, AdminAuditAction.HOME_SECTION_DELETED, id.toString(), reason, before = before)
    }

    private fun apply(section: HomeSection, body: ObjectNode, creating: Boolean) {
        val nextSource = if (body.has("source")) source(body) else section.source
        val reset = creating || nextSource != section.source
        val nextName = if (body.has("name")) string(body, "name", 30) else section.name
        val nextLimit = integer(body, "itemLimit", 1, 20) ?: section.itemLimit
        val nextOrder = integer(body, "sortOrder", 1, Int.MAX_VALUE) ?: section.sortOrder
        val nextActive = boolean(body, "isActive") ?: section.isActive
        var nextStories = section.stories.toSet()
        var nextGenres = section.genres.toSet()
        var nextOriginal = section.originalOnly
        var nextSort = section.sortBy
        if (nextSource == HomeSectionSource.MANUAL) {
            for (field in listOf("genres", "originalOnly", "sort")) if (body.has(field)) bad(field, "MANUAL 출처에는 보낼 수 없습니다.")
            if (reset && !body.has("storyIds")) bad("storyIds", "수동 지정 스토리가 필요합니다.")
            if (body.has("storyIds")) {
                val ids = strings(body, "storyIds", 1, 20).map { raw ->
                    try { UUID.fromString(raw) } catch (_: IllegalArgumentException) { bad("storyIds", "스토리 식별자가 올바르지 않습니다.") }
                }
                if (ids.toSet().size != ids.size) bad("storyIds", "중복 스토리는 지정할 수 없습니다.")
                val found = stories.findAllByPublicIdInAndDeletedAtIsNull(ids).associateBy { it.publicId }
                if (found.size != ids.size) bad("storyIds", "없거나 삭제된 스토리입니다.")
                nextStories = ids.mapIndexed { index, uuid -> HomeSectionStory(found.getValue(uuid).id, index + 1) }.toSet()
            }
            nextGenres = emptySet(); nextOriginal = false; nextSort = null
        } else {
            if (body.has("storyIds")) bad("storyIds", "FILTER 출처에는 보낼 수 없습니다.")
            if (reset) {
                if (!body.has("sort")) bad("sort", "필터 정렬이 필요합니다.")
                nextStories = emptySet(); nextGenres = emptySet(); nextOriginal = false
            }
            if (body.has("sort")) nextSort = StoryListSort.from(string(body, "sort", 10))
            boolean(body, "originalOnly")?.let { nextOriginal = it }
            if (body.has("genres")) {
                val input = strings(body, "genres", 0, 10)
                nextGenres = if (input.isEmpty()) emptySet() else genres.normalize(input, maxCount = 10).toSet()
            }
        }
        // 검증 조회가 일으키는 자동 flush에서 출처와 정렬 CHECK가 어긋나지 않도록 검증 후 한 번에 적용한다.
        section.name = nextName; section.source = nextSource; section.sortBy = nextSort
        section.itemLimit = nextLimit; section.sortOrder = nextOrder; section.isActive = nextActive; section.originalOnly = nextOriginal
        section.stories.clear(); section.stories.addAll(nextStories)
        section.genres.clear(); section.genres.addAll(nextGenres)
    }

    private fun storyMap(all: List<HomeSection>) = stories.findAllById(all.flatMap { section -> section.stories.map { it.storyId } }.toSet()).associateBy { it.id }
    private fun state(section: HomeSection, byId: Map<Long, Story>): ObjectNode = mapper.createObjectNode()
        .put("name", section.name).put("source", section.source.name).put("originalOnly", section.originalOnly)
        .put("sort", section.sortBy?.parameter).put("itemLimit", section.itemLimit).put("isActive", section.isActive).put("sortOrder", section.sortOrder)
        .also { node ->
            node.set("genres", mapper.valueToTree(section.genres.sorted()))
            node.set("storyIds", mapper.valueToTree(section.stories.sortedBy { it.sortOrder }.mapNotNull { byId[it.storyId]?.publicId?.toString() }))
        }
    private fun response(section: HomeSection, byId: Map<Long, Story>): ObjectNode = state(section, byId).also { node ->
        node.remove("storyIds")
        node.put("id", section.publicId.toString()).put("createdAt", section.createdAt.toString()).put("updatedAt", section.updatedAt.toString())
        node.set("stories", mapper.valueToTree(section.stories.sortedBy { it.sortOrder }.mapNotNull { entry ->
            byId[entry.storyId]?.takeIf { it.deletedAt == null }?.let { story ->
                mapOf("storyId" to story.publicId.toString(), "title" to story.title, "isListed" to listed(story))
            }
        }))
    }
    private fun listed(story: Story) = story.isPubliclyVisible() && story.deletedAt == null
    private fun owned(id: UUID) = sections.findByPublicIdForUpdate(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "홈 섹션을 찾을 수 없습니다.")
    private fun nextOrder() = try { Math.addExact(sections.maxSortOrder(), 1) } catch (_: ArithmeticException) { bad("sortOrder", "노출 순서 범위를 초과했습니다.") }
    private fun reason(body: ObjectNode): String = body["reason"]?.takeIf { it.isString }?.asText()
        ?.takeIf { it.isNotBlank() && it.length <= 500 } ?: bad("reason", "사유는 1~500자여야 합니다.")
    private fun source(body: ObjectNode) = try { HomeSectionSource.valueOf(string(body, "source", 10)) }
        catch (_: IllegalArgumentException) { bad("source", "출처가 올바르지 않습니다.") }
    private fun string(body: ObjectNode, field: String, max: Int): String = body[field]?.takeIf { it.isString }?.asText()?.trim()
        ?.takeIf { it.length in 1..max } ?: bad(field, "1~${max}자여야 합니다.")
    private fun integer(body: ObjectNode, field: String, min: Int, max: Int): Int? {
        if (!body.has(field)) return null
        val value = body[field] ?: bad(field, "정수가 필요합니다.")
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.asInt() !in min..max) bad(field, "${min}~${max}의 정수가 필요합니다.")
        return value.asInt()
    }
    private fun boolean(body: ObjectNode, field: String): Boolean? {
        if (!body.has(field)) return null
        return body[field]?.takeIf { it.isBoolean }?.asBoolean() ?: bad(field, "불리언 값이 필요합니다.")
    }
    private fun strings(body: ObjectNode, field: String, min: Int, max: Int): List<String> {
        val value: JsonNode = body[field]?.takeIf { it.isArray } ?: bad(field, "배열이 필요합니다.")
        if (value.size() !in min..max || value.any { !it.isString }) bad(field, "문자열 배열의 크기가 올바르지 않습니다.")
        return value.toList().map { it.asText() }
    }
    private fun bad(field: String, message: String): Nothing = throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message,
        details = listOf(ApiErrorDetail(field, message)))
}
