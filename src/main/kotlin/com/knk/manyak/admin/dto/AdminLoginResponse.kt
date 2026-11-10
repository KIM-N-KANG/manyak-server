package com.knk.manyak.admin.dto

import com.knk.manyak.auth.dto.AdminMemberSummary

data class AdminLoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val admin: AdminMemberSummary,
    val tokenType: String = "Bearer",
)
