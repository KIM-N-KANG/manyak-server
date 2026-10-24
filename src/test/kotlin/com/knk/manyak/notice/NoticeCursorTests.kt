package com.knk.manyak.notice

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.notice.service.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

class NoticeCursorTests {
    private fun codec(secret: String = "notice-test-secret-at-least-32-bytes-long") = NoticeCursorCodec(AuthProperties(secret = secret, issuer = "notice-tests", accessTtl = java.time.Duration.ofMinutes(30), refreshTtl = java.time.Duration.ofDays(30)))
    @Test fun `커서는 마이크로초와 동률 내부 ID를 봉인하고 변조나 다른 키는 거절한다`() {
        val cursor = NoticeCursor(Instant.parse("2026-10-22T00:00:00.123456Z"), 42)
        val codec = codec(); val encoded = codec.encode(cursor)
        assertThat(codec.decode(encoded)).isEqualTo(cursor)
        assertThat(encoded).doesNotContain("|42", cursor.publishedAt.toString())
        for (invalid in listOf("", "bad", encoded.dropLast(4), "x".repeat(513))) {
            assertThatThrownBy { codec.decode(invalid) }.isInstanceOf(ResponseStatusException::class.java)
        }
        assertThatThrownBy { codec("other-notice-test-secret-at-least-32-bytes").decode(encoded) }.isInstanceOf(ResponseStatusException::class.java)
    }
}
