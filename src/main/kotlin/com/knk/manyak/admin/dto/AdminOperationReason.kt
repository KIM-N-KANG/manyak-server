package com.knk.manyak.admin.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AdminOperationReason(
    @field:NotBlank @field:Size(max = 500) val reason: String,
)
