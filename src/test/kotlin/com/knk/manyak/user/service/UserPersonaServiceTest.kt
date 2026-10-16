package com.knk.manyak.user.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.user.dto.CreateUserPersonaRequest
import com.knk.manyak.user.dto.UpdateUserPersonaRequest
import com.knk.manyak.user.repository.UserPersonaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class UserPersonaServiceTest {
    private val personas = mock(UserPersonaRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val service = UserPersonaService(personas, users)
    private val missingUserId = 123L
    private val personaId = UUID.randomUUID().toString()

    @Test
    fun `회원이 없는 생성은 서비스에서 고정 문구의 401로 거절한다`() {
        assertMissingUser { service.create(missingUserId, CreateUserPersonaRequest("주인공", "설명")) }
    }

    @Test
    fun `회원이 없는 수정은 서비스에서 고정 문구의 401로 거절한다`() {
        assertMissingUser { service.update(missingUserId, personaId, UpdateUserPersonaRequest(name = "주인공")) }
    }

    @Test
    fun `회원이 없는 삭제는 서비스에서 고정 문구의 401로 거절한다`() {
        assertMissingUser { service.delete(missingUserId, personaId) }
    }

    private fun assertMissingUser(action: () -> Unit) {
        `when`(users.findByIdForUpdate(missingUserId)).thenReturn(null)
        val exception = assertThrows<ResponseStatusException> { action() }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(exception.reason).isEqualTo("유효하지 않은 인증입니다.")
        verify(users).findByIdForUpdate(missingUserId)
        verifyNoInteractions(personas)
    }
}
