package com.knk.manyak.story.submission

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.push.outbox.PushMessage
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal fun StoryModerationCompleted.pushData(base: String): Map<String, String> = buildMap {
    put("type", "STORY_MODERATION_COMPLETED")
    put("submissionId", submissionId)
    put("status", status.name)
    storyId?.let { put("storyId", it) }
    put("deepLink", "${base.trimEnd('/')}/" + if (storyId != null) "stories/$storyId/edit" else "studio/story/general?submissionId=$submissionId")
}

@Component
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "local", matchIfMissing = true)
class ModerationPushListener(
    private val users: UserRepository,
    private val sender: FcmPushSender,
    @Qualifier("pushExecutor") private val executor: Executor,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val base: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun completed(event: StoryModerationCompleted) {
        try {
            executor.execute {
                try {
                    if (users.findById(event.userId).orElse(null)?.servicePushEnabled == true) sender.sendToUser(event.userId, event.pushData(base))
                } catch (ex: RuntimeException) { log.warn("moderation_push_failed submission={} error={}", event.submissionId, ex.javaClass.simpleName) }
            }
        } catch (_: RejectedExecutionException) { log.warn("moderation_push_rejected submission={}", event.submissionId) }
    }
}

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
