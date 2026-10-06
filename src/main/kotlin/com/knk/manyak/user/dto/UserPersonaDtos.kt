package com.knk.manyak.user.dto

import java.time.Instant

data class CreateUserPersonaRequest(val name: String? = null, val description: String? = null)
data class UpdateUserPersonaRequest(val name: String? = null, val description: String? = null)
data class UserPersonaResponse(val id: String, val name: String, val description: String, val createdAt: Instant, val updatedAt: Instant)
data class PersonaSnapshot(val name: String, val description: String)
