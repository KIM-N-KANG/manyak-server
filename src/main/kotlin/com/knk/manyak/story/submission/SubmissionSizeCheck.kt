package com.knk.manyak.story.submission

import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.image.service.UploadedImageStorage
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Component
class SubmissionSizeCheck(
    private val storage: UploadedImageStorage,
    private val mapper: ObjectMapper,
    @Value("\${manyak.ai.moderation.request-budget-bytes:41943040}") private val budget: Long,
) {
    init { require(budget > 0) { "Moderation request-budget-bytes must be positive" } }

    fun check(form: JsonNode, aiInput: JsonNode, validatedSizes: Map<String, Long>, isUpdate: Boolean) {
        val input = aiInput.deepCopy() as ObjectNode
        var imageBytes = 0L
        fun include(source: JsonNode, target: ObjectNode, keyField: String, urlField: String) {
            val key = source.path(keyField).takeIf { it.isString }?.asText()
            val url = source.path(urlField).takeIf { it.isString }?.asText()?.takeIf { it.isNotBlank() }
            if (key == null && url == null) return
            val bytes = if (key != null) {
                // 제출 prefix·형식 검증에서 받은 HEAD 값을 재사용한다. 추정 URL도 실제 불변 복사본과 같은 길이다.
                val copiedKey = "${key.substringBefore("/uploaded/")}/uploaded/moderated/$UUID_PLACEHOLDER.${key.substringAfterLast('.')}"
                target.put(urlField, requireNotNull(storage.serveUrlOf(copiedKey)))
                requireNotNull(validatedSizes[key])
            } else retainedBytes(requireNotNull(url))
            // 매우 큰/잘못된 메타데이터에서도 합계 연산이 overflow하여 통과하지 않게 한다.
            if (bytes < 0 || bytes > budget || imageBytes > budget - bytes) tooLarge()
            imageBytes += bytes
        }
        include(form, input, "thumbnailObjectKey", "thumbnailUrl")
        form.path("characters").forEachIndexed { c, character -> character.path("images").forEachIndexed { i, source ->
            include(source, input.path("characters").path(c).path("images").path(i) as ObjectNode, "objectKey", "imageUrl")
        } }
        input.put("submissionId", UUID_PLACEHOLDER)
        if (isUpdate) input.put("storyId", UUID_PLACEHOLDER)
        val textBytes = mapper.writeValueAsBytes(input).size.toLong()
        // ceil(이미지 전체 byte * 4/3) + 검수 입력 JSON UTF-8 byte. 프롬프트 여유는 예산에서 제외했다.
        val base64Bytes = imageBytes + (imageBytes + 2) / 3
        if (textBytes > budget || base64Bytes > budget - textBytes) tooLarge()
    }

    private fun retainedBytes(url: String): Long = try {
        // 서버의 동일 asset 저장소 URL만 객체 키로 바꾼다. HEAD 실패·미설정·외부 URL은 보수적으로 계산한다.
        val base = storage.serveUrlOf("")?.takeIf { it.isNotBlank() }
        val key = base?.let { if (url.startsWith(it)) url.removePrefix(it).takeIf(String::isNotBlank) else null }
        key?.let { storage.head(it)?.contentLength?.takeIf { length -> length >= 0 } } ?: FALLBACK_BYTES
    } catch (_: Exception) { FALLBACK_BYTES }

    private fun tooLarge(): Nothing = throw CodedResponseStatusException(
        HttpStatus.BAD_REQUEST, ApiErrorCodes.IMAGES_TOO_LARGE, "이미지 크기나 장수를 줄여 주세요.",
    )
    private companion object {
        const val FALLBACK_BYTES = 5L * 1024 * 1024
        const val UUID_PLACEHOLDER = "00000000-0000-0000-0000-000000000000"
    }
}
