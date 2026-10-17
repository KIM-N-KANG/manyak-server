package com.knk.manyak.push.outbox

import com.knk.manyak.auth.repository.UserRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 회차 전체를 커밋한다. 중간 회원 기록에 실패하면 앞선 회원의 요청도 함께 롤백한다. */
@Service
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class ScheduledPushOutboxWriter(
    private val users: UserRepository,
    private val store: PushOutboxStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun record(
        targetIds: List<Long>,
        data: Map<String, String>,
        scenario: String,
        occurrence: String,
        expiresAt: Instant? = null,
    ): Int {
        val now = clock.instant()
        return targetIds.sumOf { userId ->
            // 자격은 소비자가 재확인한다. 여기서는 내부 ID를 공개 식별자로만 변환한다.
            val recipientId = users.findById(userId).orElseThrow().publicId.toString()
            val messageId = when (scenario) {
                "attendance" -> "attendance:$recipientId:$occurrence"
                "promotion" -> "promotion:$occurrence:$recipientId"
                else -> error("Unknown scheduled push scenario: $scenario")
            }
            store.insert(PushMessage(
                messageId = messageId,
                recipientId = recipientId,
                kind = "MARKETING",
                type = data.getValue("type"),
                data = data,
                expiresAt = expiresAt,
                requestId = "unknown",
                sessionId = "unknown",
            ), now)
        }
    }
}
