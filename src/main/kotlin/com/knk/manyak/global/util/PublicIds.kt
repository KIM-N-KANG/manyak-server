package com.knk.manyak.global.util

import java.util.UUID

/** 외부 식별자(public_id) 문자열을 UUID로 읽는다. null이거나 UUID 형식이 아니면 null이다. */
fun parsePublicIdOrNull(raw: String?): UUID? {
    if (raw == null) return null
    return try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}
