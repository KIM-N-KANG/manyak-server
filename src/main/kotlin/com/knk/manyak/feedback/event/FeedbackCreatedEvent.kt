package com.knk.manyak.feedback.event

import com.knk.manyak.feedback.entity.Platform
import java.time.Instant

/**
 * 피드백이 저장된 직후 발행되는 도메인 이벤트.
 *
 * 엔티티 대신 불변 스냅샷을 담아, 커밋 이후(다른 스레드 포함)에도 안전하게 소비되도록 한다.
 */
data class FeedbackCreatedEvent(
    val id: Long,
    val body: String,
    val email: String?,
    val platform: Platform?,
    val appVersion: String?,
    val createdAt: Instant,
)
