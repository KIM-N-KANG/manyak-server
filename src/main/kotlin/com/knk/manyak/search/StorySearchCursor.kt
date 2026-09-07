package com.knk.manyak.search

import org.opensearch.client.opensearch._types.FieldValue
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class StorySearchCursor(val score: Double, val createdAt: Long, val publicId: UUID) {
    fun encode(q: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${hash(q)}|$score|$createdAt|$publicId".toByteArray(Charsets.UTF_8))

    fun sortValues(): List<FieldValue> = listOf(FieldValue.of(score), FieldValue.of(createdAt), FieldValue.of(publicId.toString()))

    companion object {
        fun decode(raw: String, q: String): StorySearchCursor {
            if (raw.length > 512) throw badCursor()
            val parts = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).split('|') }
                .getOrElse { throw badCursor() }
            if (parts.size != 4 || parts[0] != hash(q)) throw badCursor()
            val score = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: throw badCursor()
            val createdAt = parts[2].toLongOrNull() ?: throw badCursor()
            val id = runCatching { UUID.fromString(parts[3]) }.getOrElse { throw badCursor() }
            if (id.toString() != parts[3]) throw badCursor()
            return StorySearchCursor(score, createdAt, id)
        }

        fun fromSort(values: List<FieldValue>): StorySearchCursor {
            require(values.size == 3) { "검색 정렬값은 세 개여야 합니다." }
            val score = if (values[0].isDouble) values[0].doubleValue() else values[0].longValue().toDouble()
            return StorySearchCursor(score, values[1].longValue(), UUID.fromString(values[2].stringValue()))
        }

        private fun hash(q: String): String = MessageDigest.getInstance("SHA-256")
            .digest(q.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun badCursor() = ResponseStatusException(HttpStatus.BAD_REQUEST, "커서가 올바르지 않습니다.")
    }
}
