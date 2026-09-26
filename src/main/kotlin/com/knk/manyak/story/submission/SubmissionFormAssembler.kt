package com.knk.manyak.story.submission

import com.knk.manyak.story.dto.*
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.service.*
import com.knk.manyak.image.service.UploadedImageKind
import com.knk.manyak.image.service.UploadedImageStorage
import jakarta.validation.Validator
import org.springframework.stereotype.Component
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** 라이브를 변경하지 않고 제출 검증·편집 폼·AI 입력을 조립한다. */
@Component
class SubmissionFormAssembler(
    private val mapper: ObjectMapper,
    private val validator: Validator,
    private val edit: StoryEditService,
    private val images: StoryImageAccess,
    private val storage: UploadedImageStorage,
) {
    fun create(request: CreateGeneralStoryRequest, userId: Long, validate: Boolean = true): ObjectNode {
        if (validate) validateBean(request)
        val form = mapper.valueToTree<ObjectNode>(request)
        if (validate) {
            requireDistinctMainEventNames(request.mainEvents.map { it.name })
            request.startSettings.forEach { requireDistinctEndingNames(it.endings.map { ending -> ending.name }) }
        }
        form.set("characters", characters(request.characters, mapper.createArrayNode(), null, userId, validate, false))
        request.thumbnailObjectKey?.let { form.put("thumbnailUrl", url(it, UploadedImageKind.COVER, null, userId, validate)) }
        form.put("thumbnailModerationStatus", "APPROVED")
        form.putNull("submission")
        return form
    }

    fun update(story: Story, request: UpdateStoryRequest, userId: Long, validate: Boolean = true, allowDeletedImages: Boolean = false, previousImageIds: Set<String> = emptySet()): ObjectNode {
        if (validate) validateBean(request)
        val form = mapper.valueToTree<ObjectNode>(edit.getEditForm(story.publicId.toString(), userId))
        if (validate) {
            if (request.title?.isBlank() == true || request.oneLineIntro?.isBlank() == true) bad("제목·한 줄 소개는 비어 있을 수 없습니다.")
            if (request.visibility != null && request.visibility != story.visibility && story.status != StoryStatus.PUBLISHED) bad("등록되지 않은 스토리는 공개 범위를 바꿀 수 없습니다.")
            request.mainEvents?.let { requireDistinctMainEventNames(it.map { item -> item.name }) }
            request.startSettings?.let { inputs ->
                distinctIds(inputs.map { it.id })
                val ids = form.path("startSettings").toList().map { it.path("id").asText() }.toSet()
                inputs.forEach {
                    if (it.id != null && it.id !in ids) bad("이 스토리에 속하지 않는 시작 설정 ID입니다.")
                    requireDistinctEndingNames(it.endings.map { ending -> ending.name })
                }
            }
        }
        val patch = mapper.valueToTree<ObjectNode>(request)
        patch.properties().forEach { (key, value) -> if (!value.isNull && key != "characters") form.set(key, value) }
        request.characters?.let { form.set("characters", characters(it, form.path("characters"), story, userId, validate, allowDeletedImages, previousImageIds)) }
        request.thumbnailObjectKey?.let { form.put("thumbnailUrl", url(it, UploadedImageKind.COVER, story, userId, validate)) }
        form.putNull("submission")
        return form
    }

    private fun characters(inputs: List<GeneralCharacterInput>, existing: JsonNode, story: Story?, userId: Long, validate: Boolean, allowDeleted: Boolean, previousImageIds: Set<String> = emptySet()): JsonNode {
        if (validate) {
            requireDistinctCharacterNames(inputs.map { it.name })
            distinctIds(inputs.map { it.id })
        }
        val result = mapper.createArrayNode()
        inputs.forEach { input ->
            val old = existing.firstOrNull { input.id != null && it.path("id").asText() == input.id }
            if (validate && input.id != null && old == null) bad("이 스토리에 속하지 않는 인물 ID입니다.")
            val character = mapper.createObjectNode().put("id", input.id).put("name", input.name)
            val priorImages = old?.path("images") ?: mapper.createArrayNode()
            val output = mapper.createArrayNode()
            val imageInputs = input.images ?: priorImages.toList().map { GeneralCharacterImageInput(id = it.path("id").asText()) }
            if (validate) distinctIds(imageInputs.map { it.id })
            imageInputs.forEach image@{ item ->
                if (validate && (!item.hasExactlyOneSource() || !item.hasImageNameWhenNew())) bad("이미지 항목의 입력이 올바르지 않습니다.")
                val prior = priorImages.firstOrNull { item.id != null && it.path("id").asText() == item.id }
                if (item.id != null && prior == null) {
                    if (allowDeleted && (!validate || item.id in previousImageIds)) return@image
                    if (validate) bad("이 인물에 속하지 않는 이미지 ID입니다.")
                    return@image
                }
                val oldName = old?.path("name")?.asText() ?: input.name
                val previousName = prior?.path("imageName")?.asText().orEmpty()
                val name = item.imageName?.takeIf { it.isNotBlank() } ?: if (previousName.startsWith("${oldName}_")) "${input.name}_${previousName.removePrefix("${oldName}_")}" else previousName
                val finalName = if (validate) requireValidImageName(input.name, name) else name
                if (validate && finalName.length > 120) bad("이미지 이름은 120자를 넘을 수 없습니다.")
                val image = mapper.createObjectNode().put("id", item.id).put("imageName", finalName).put("moderationStatus", "APPROVED")
                if (item.objectKey != null) {
                    image.put("objectKey", item.objectKey)
                    image.put("imageUrl", url(item.objectKey, UploadedImageKind.CHARACTER, story, userId, validate))
                } else image.put("imageUrl", prior?.path("imageUrl")?.asText())
                output.add(image)
            }
            val names = output.toList().map { it.path("imageName").asText() }
            if (validate && names.size != names.toSet().size) throw StoryImageAccess.duplicateImageName()
            character.set("images", output)
            result.add(character)
        }
        return result
    }

    private fun url(key: String, kind: UploadedImageKind, story: Story?, userId: Long, validate: Boolean): String? {
        if (!validate) return storage.serveUrlOf(key)
        val owner = images.resolveUserPublicId(userId)
        return if (story == null) images.resolveDraftUploadedUrl(owner, kind, key) else images.resolveUploadedUrl(story, owner, kind, key)
    }

    fun aiInput(form: JsonNode): JsonNode {
        val excluded = setOf("id", "objectKey", "thumbnailObjectKey", "visibility", "minTurns", "moderationStatus", "thumbnailModerationStatus", "submission", "sortOrder")
        fun strip(node: JsonNode): JsonNode = when {
            node.isObject -> mapper.createObjectNode().also { obj -> node.properties().forEach { (key, value) -> if (key !in excluded) obj.set(key, strip(value)) } }
            node.isArray -> mapper.createArrayNode().also { array -> node.forEach { array.add(strip(it)) } }
            else -> node
        }
        return strip(form)
    }

    /** 원본 검수 폼의 identity를 따라 현재 폼의 배열 인덱스로 옮긴다. 삭제된 대상은 제외한다. */
    fun remap(issues: List<ModerationIssue>, original: JsonNode, current: JsonNode): List<ModerationIssue> = issues.mapNotNull { issue ->
        var before: JsonNode = original
        var after: JsonNode = current
        val path = StringBuilder()
        val segments = Regex("([^.\\[\\]]+)|\\[(\\d+)\\]").findAll(issue.path)
        for (segment in segments) {
            val key = segment.groups[1]?.value
            if (key != null) {
                before = before.path(key)
                after = after.path(key)
                if (after.isMissingNode || (key == "thumbnailUrl" && before != after)) return@mapNotNull null
                if (path.isNotEmpty()) path.append('.')
                path.append(key)
            } else {
                val index = segment.groups[2]!!.value.toInt()
                val item = before.path(index)
                if (item.isMissingNode) return@mapNotNull null
                val identity = listOf("id", "objectKey", "name").firstOrNull { !item.path(it).isMissingNode && !item.path(it).isNull }
                val target = if (identity == null) index else after.indexOfFirst { it.path(identity) == item.path(identity) }
                if (target < 0 || after.path(target).isMissingNode) return@mapNotNull null
                before = item
                after = after.path(target)
                path.append('[').append(target).append(']')
            }
        }
        issue.copy(path = path.toString())
    }

    private fun validateBean(value: Any) {
        if (validator.validate(value).isNotEmpty()) bad("제출 입력이 올바르지 않습니다.")
    }
    private fun distinctIds(ids: List<String?>) {
        val present = ids.filterNotNull()
        if (present.size != present.toSet().size) bad("ID는 요청 내에서 중복될 수 없습니다.")
    }
    private fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
