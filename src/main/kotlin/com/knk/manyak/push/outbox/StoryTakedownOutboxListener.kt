package com.knk.manyak.push.outbox

import com.knk.manyak.auth.service.AccountQueryService
import com.knk.manyak.push.event.pushData
import com.knk.manyak.story.submission.StoryTakenDown
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class StoryTakedownOutboxListener(
    private val accounts: AccountQueryService,
    private val store: PushOutboxStore,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val base: String,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun takenDown(event: StoryTakenDown) {
        val recipientId = accounts.requirePublicIdOf(event.userId)
        store.insert(PushMessage(messageId = event.messageId, recipientId = recipientId.toString(), kind = "SERVICE",
            type = "STORY_TAKEN_DOWN", data = event.pushData(base), requestId = MDC.get("request_id") ?: "unknown",
            sessionId = MDC.get("session_id") ?: "unknown"), Instant.now())
    }
}
