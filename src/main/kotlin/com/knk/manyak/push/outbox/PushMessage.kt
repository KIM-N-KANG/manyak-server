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

/**
 * 현재 추적 컨텍스트를 전송 헤더로 내보낸다(KNK-1552). 본문의 requestId와 별개로 메시지 속성에 실려,
 * 소비자가 같은 트레이스 아래에 스팬을 이어 붙인다. 추적이 꺼져 있으면 아무것도 쓰지 않는다.
 */
fun interface TraceHeaders {
    fun inject(sink: (name: String, value: String) -> Unit)

    companion object {
        val NONE = TraceHeaders { }
    }
}
