package com.knk.manyak.admin.dto

import jakarta.validation.constraints.NotBlank

data class AdminLoginRequest(@field:NotBlank val idToken: String)
