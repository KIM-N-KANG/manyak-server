package com.knk.manyak.push.outbox

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.event.pushData
import com.knk.manyak.story.submission.StoryModerationCompleted
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
class ModerationOutboxListener(
    private val users: UserRepository,
    private val store: PushOutboxStore,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val base: String,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun completed(event: StoryModerationCompleted) {
        val user = users.findById(event.userId).orElseThrow()
        store.insert(PushMessage(messageId = "story-moderation:${event.submissionId}:${event.attempt}",
            recipientId = user.publicId.toString(), kind = "SERVICE", type = "STORY_MODERATION_COMPLETED",
            data = event.pushData(base), requestId = MDC.get("request_id") ?: "unknown", sessionId = MDC.get("session_id") ?: "unknown"), Instant.now())
    }
}
