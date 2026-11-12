package com.knk.manyak.global.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class RequireUserTest {
    @Test
    fun `사용자 id가 없으면 고정 문구의 401로 거절한다`() {
        val exception = assertThrows<ResponseStatusException> { requireUser(null) }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(exception.reason).isEqualTo("유효하지 않은 인증입니다.")
    }

    @Test
    fun `사용자 id가 있으면 그대로 돌려준다`() {
        assertThat(requireUser(42L)).isEqualTo(42L)
    }
}
