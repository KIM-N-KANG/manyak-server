package com.knk.manyak.global.error

import java.time.Instant

data class ApiErrorResponse(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val details: List<ApiErrorDetail> = emptyList(),
)

data class ApiErrorDetail(
    val field: String? = null,
    val message: String,
)
