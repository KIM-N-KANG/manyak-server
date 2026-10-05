package com.knk.manyak.push.outbox

import java.util.concurrent.CompletableFuture

data class PushMessage(
    val messageId: String,
    val recipientId: String,
    val kind: String = "SERVICE",
    val type: String = "STORY_COMPLETED",
    val data: Map<String, String>,
    val requestId: String,
    val sessionId: String,
    val schemaVersion: Int = 1,
)

/** 브로커의 확인까지 비동기로 완료한다. */
fun interface PushPublisher {
    fun publish(message: PushMessage): CompletableFuture<Unit>
}
