package com.knk.manyak.story.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** 시드와 마지막 정렬 위치를 담은 탐색 keyset 커서. */
data class StoryExploreCursor(val seed: Long, val sortValue: String, val publicId: UUID) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("x:$seed:$sortValue:$publicId".toByteArray(Charsets.UTF_8))

    companion object {
        fun hash(seed: Long, publicId: UUID): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("MD5").digest("$seed$publicId".toByteArray(Charsets.UTF_8)),
        )

        fun decode(raw: String): StoryExploreCursor {
            if (raw.length > 256) throw badCursor()
            val parts = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).split(':') }
                .getOrElse { throw badCursor() }
            if (parts.size != 4 || parts[0] != "x" || !parts[2].matches(Regex("[0-9a-f]{32}"))) throw badCursor()
            val seed = parts[1].toLongOrNull() ?: throw badCursor()
            val id = runCatching { UUID.fromString(parts[3]) }.getOrElse { throw badCursor() }
            if (!id.toString().equals(parts[3], ignoreCase = true)) throw badCursor()
            return StoryExploreCursor(seed, parts[2], id)
        }

        private fun badCursor() = ResponseStatusException(HttpStatus.BAD_REQUEST, "탐색 커서가 올바르지 않습니다.")
    }
}
