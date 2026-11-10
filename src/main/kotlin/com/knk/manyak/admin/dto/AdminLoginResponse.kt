package com.knk.manyak.admin.dto

data class AdminLoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val admin: AdminMemberSummary,
    val tokenType: String = "Bearer",
)
