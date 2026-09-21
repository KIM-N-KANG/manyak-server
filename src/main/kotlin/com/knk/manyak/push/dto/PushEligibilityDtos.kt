package com.knk.manyak.push.dto

import com.knk.manyak.push.entity.PushPlatform

enum class PushKind {
    SERVICE,
    MARKETING,
}

data class PushEligibilityResponse(
    val allowed: Boolean,
    val reason: String,
    val tokens: List<PushEligibilityToken> = emptyList(),
)

data class PushEligibilityToken(
    val token: String,
    val platform: PushPlatform,
)
