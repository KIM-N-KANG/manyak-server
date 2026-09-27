package com.knk.manyak.story.submission

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.convert.DurationStyle
import org.springframework.stereotype.Component

@Component
class SubmissionRetryPolicy(@Value("\${manyak.ai.moderation.retry-delays:1m,5m}") intervals: String) {
    val delays = intervals.split(',').map { DurationStyle.detectAndParse(it.trim()) }
    init {
        require(delays.size == 2 && delays.all { !it.isNegative && !it.isZero }) {
            "Moderation retry-delays must contain exactly two positive durations"
        }
    }
    fun isTransient(code: String, issues: List<ModerationIssue> = emptyList(), imageErrors: List<ModerationImageError> = emptyList()): Boolean =
        code in setOf("IMAGE_DOWNLOAD_FAILED", "MODEL_CALL_FAILED", "MODERATION_UNAVAILABLE") &&
            issues.isEmpty() && imageErrors.none { it.errorCode in setOf("IMAGE_INVALID", "IMAGE_UNREADABLE") }
}
