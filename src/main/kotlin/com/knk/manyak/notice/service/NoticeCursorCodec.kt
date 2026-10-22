package com.knk.manyak.notice.service

import com.knk.manyak.auth.config.AuthProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class NoticeCursor(val publishedAt: Instant, val id: Long)

/** 순차 PK가 커서에서 드러나지 않게 봉인한다. 다른 도메인 커서와 키 용도를 분리한다. */
@Component
class NoticeCursorCodec(properties: AuthProperties) {
    private val key = SecretKeySpec(Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256"))
    }.doFinal("notice-cursor-v1".toByteArray()), "AES")
    private val random = SecureRandom()
    fun encode(cursor: NoticeCursor): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val encrypted = cipher.doFinal("${cursor.publishedAt}|${cursor.id}".toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + encrypted)
    }
    fun decode(cursor: String): NoticeCursor = try {
        require(cursor.length <= 512)
        val bytes = Base64.getUrlDecoder().decode(cursor)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        val parts = String(cipher.doFinal(bytes, 12, bytes.size - 12)).split('|')
        require(parts.size == 2)
        NoticeCursor(Instant.parse(parts[0]), parts[1].toLong().also { require(it > 0) })
    } catch (_: Exception) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "커서가 올바르지 않습니다.") }
}
