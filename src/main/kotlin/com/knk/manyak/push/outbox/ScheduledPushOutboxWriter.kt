package com.knk.manyak.push.outbox

import com.knk.manyak.auth.service.AccountQueryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 회차 전체를 커밋한다. 중간 회원 기록에 실패하면 앞선 회원의 요청도 함께 롤백한다. */
@Service
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class ScheduledPushOutboxWriter(
    private val accounts: AccountQueryService,
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
        kind: String = "MARKETING",
        expiresAtByUserId: Map<Long, Instant> = emptyMap(),
    ): Int {
        val now = clock.instant()
        // 자격은 소비자가 재확인한다. 여기서는 내부 ID를 공개 식별자로만 변환한다. 대상이 사라졌으면 기록 전에 실패해 회차가 롤백된다.
        val publicIds = accounts.requirePublicIdsOf(targetIds)
        return targetIds.sumOf { userId ->
            val recipientId = publicIds.getValue(userId).toString()
            val messageId = when (scenario) {
                "attendance" -> "attendance:$recipientId:$occurrence"
                "credit-expiry" -> "credit-expiry:$recipientId:$occurrence"
                "promotion" -> "promotion:$occurrence:$recipientId"
                "notice" -> "notice:$occurrence:$recipientId"
                else -> error("Unknown scheduled push scenario: $scenario")
            }
            store.insert(PushMessage(
                messageId = messageId,
                recipientId = recipientId,
                kind = kind,
                type = data.getValue("type"),
                data = data,
                expiresAt = expiresAtByUserId[userId] ?: expiresAt,
                requestId = "unknown",
                sessionId = "unknown",
            ), now)
        }
    }
}
