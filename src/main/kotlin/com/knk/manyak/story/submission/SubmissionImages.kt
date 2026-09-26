package com.knk.manyak.story.submission

import com.knk.manyak.image.service.UploadedImageStorage
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

data class SubmissionWork(val form: JsonNode, val copies: Map<String, String>)

/** 외부 PUT 권한이 없는 매번 새 키로 복사한다. S3 왕복 동안 DB 트랜잭션을 열지 않는다. */
@Component
class SubmissionImages(private val storage: UploadedImageStorage, private val forms: SubmissionFormAssembler) {
    fun prepare(work: SubmissionWork, save: (String, String) -> String?): JsonNode? {
        val copies = work.copies.toMutableMap()
        for (source in newKeys(work.form)) {
            if (source in copies) continue
            val prefix = source.substringBefore("/uploaded/")
            require(prefix in setOf("thumbnails", "characters"))
            val extension = source.substringAfterLast('.')
            require(extension in setOf("jpg", "jpeg", "png", "webp"))
            val destination = "$prefix/uploaded/moderated/${UUID.randomUUID()}.$extension"
            storage.copy(source, destination)
            copies[source] = save(source, destination) ?: return null
        }
        val form = work.form.deepCopy() as ObjectNode
        form.path("thumbnailObjectKey").takeIf { it.isString }?.asText()?.let { form.put("thumbnailUrl", url(copies.getValue(it))) }
        form.path("characters").forEach { character -> character.path("images").forEach { image ->
            image.path("objectKey").takeIf { it.isString }?.asText()?.let { (image as ObjectNode).put("imageUrl", url(copies.getValue(it))) }
        } }
        return forms.aiInput(form)
    }
    fun approvedUrls(work: SubmissionWork): Map<String, String> = newKeys(work.form).associateWith { source ->
        url(work.copies.getValue(source)) // 복사 결과가 없으면 원본 URL로 폴백하지 않는다.
    }
    private fun url(key: String): String = storage.serveUrlOf(key) ?: error("Image storage is unavailable")
    companion object {
        fun newKeys(form: JsonNode): Set<String> = buildSet {
            form.path("thumbnailObjectKey").takeIf { it.isString }?.asText()?.let(::add)
            form.path("characters").forEach { character -> character.path("images").forEach { image ->
                image.path("objectKey").takeIf { it.isString }?.asText()?.let(::add)
            } }
        }
    }
}
