package com.knk.manyak.user.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.error.ApiErrorDetail
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.security.requireActiveStatus
import com.knk.manyak.user.dto.*
import com.knk.manyak.user.entity.UserPersona
import com.knk.manyak.user.repository.UserPersonaRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class UserPersonaService(private val personas: UserPersonaRepository, private val users: UserRepository) {
    @Transactional(readOnly = true)
    fun list(userId: Long): List<UserPersonaResponse> = personas.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId).map(::response)

    @Transactional
    fun create(userId: Long, request: CreateUserPersonaRequest): UserPersonaResponse {
        val name = validated(request.name, "name", 20)
        val description = validated(request.description, "description", 1000)
        requireActiveUser(userId)
        if (personas.countByUserIdAndDeletedAtIsNull(userId) >= 10) throw ResponseStatusException(HttpStatus.CONFLICT, "페르소나는 최대 10개까지 저장할 수 있습니다.")
        return response(personas.saveAndFlush(UserPersona(userId = userId, name = name, description = description)))
    }

    @Transactional
    fun update(userId: Long, id: String, request: UpdateUserPersonaRequest): UserPersonaResponse {
        requireActiveUser(userId)
        val persona = owned(userId, id)
        if (request.name == null && request.description == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "바꿀 항목이 없습니다.")
        request.name?.let { persona.name = validated(it, "name", 20) }
        request.description?.let { persona.description = validated(it, "description", 1000) }
        persona.updatedAt = Instant.now()
        personas.flush()
        return response(persona)
    }

    @Transactional
    fun delete(userId: Long, id: String) {
        requireActiveUser(userId)
        owned(userId, id).deletedAt = Instant.now()
    }

    @Transactional
    fun snapshot(userId: Long, id: String): PersonaSnapshot = owned(userId, id).let { PersonaSnapshot(it.name, it.description) }

    // 회원 상태를 재검사하며 잠금 순서는 users → user_personas로 통일한다.
    private fun requireActiveUser(userId: Long) {
        val user = users.findByIdForUpdate(userId) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        requireActiveStatus(user.status)
    }

    private fun owned(userId: Long, id: String): UserPersona {
        val uuid = try { UUID.fromString(id) } catch (_: IllegalArgumentException) { missing() }
        return personas.findOwnedForUpdate(uuid, userId) ?: missing()
    }
    private fun missing(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "페르소나를 찾을 수 없습니다.")
    private fun validated(raw: String?, field: String, max: Int): String {
        val value = raw?.trim()
        if (value == null || value.length !in 1..max) throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "페르소나 입력이 올바르지 않습니다.", details = listOf(ApiErrorDetail(field, "앞뒤 공백을 제외하고 1~${max}자여야 합니다.")))
        return value
    }
    private fun response(p: UserPersona) = UserPersonaResponse(p.publicId.toString(), p.name, p.description, p.createdAt, p.updatedAt)
}
