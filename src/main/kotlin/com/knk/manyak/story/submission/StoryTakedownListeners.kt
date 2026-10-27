package com.knk.manyak.story.submission

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.push.outbox.PushMessage
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.service.pushDeepLink
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

internal fun StoryTakenDown.pushData(base: String): Map<String, String> {
    val notificationTitle = if (title.codePointCount(0, title.length) > 100)
        title.substring(0, title.offsetByCodePoints(0, 99)) + "…" else title
    val last = notificationTitle.lastOrNull()
    val particle = if (last == null || last !in '가'..'힣') "은(는)" else if ((last.code - '가'.code) % 28 == 0) "는" else "은"
    return mapOf("type" to "STORY_TAKEN_DOWN", "storyId" to storyId.toString(), "title" to "스토리 공개가 제한됐어요",
        "body" to "「$notificationTitle」$particle $reason 사유로 공개할 수 없어요. 내용을 수정해 검수를 통과하면 공개할 수 있어요.",
        "deepLink" to pushDeepLink(base, "/studio", "STORY_TAKEN_DOWN"))
}

@Component
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "local", matchIfMissing = true)
class StoryTakedownPushListener(
    private val users: UserRepository,
    private val sender: FcmPushSender,
    @Qualifier("pushExecutor") private val executor: Executor,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val base: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun takenDown(event: StoryTakenDown) {
        try {
            executor.execute {
                try {
                    val data = event.pushData(base)
                    if (users.findById(event.userId).orElse(null)?.servicePushEnabled == true) {
                        sender.sendToUser(event.userId, data, messageId = event.messageId)
                    } else sender.recordDiscarded(event.userId, data, event.messageId, "SERVICE_PUSH_DISABLED")
                } catch (ex: RuntimeException) {
                    log.warn("story_takedown_push_failed story_public_id={} error={}", event.storyId, ex.javaClass.simpleName)
                }
            }
        } catch (_: RejectedExecutionException) {
            log.warn("story_takedown_push_rejected story_public_id={}", event.storyId)
        }
    }
}

@Component
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class StoryTakedownOutboxListener(
    private val users: UserRepository,
    private val store: PushOutboxStore,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val base: String,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun takenDown(event: StoryTakenDown) {
        val user = users.findById(event.userId).orElseThrow()
        store.insert(PushMessage(messageId = event.messageId, recipientId = user.publicId.toString(), kind = "SERVICE",
            type = "STORY_TAKEN_DOWN", data = event.pushData(base), requestId = MDC.get("request_id") ?: "unknown",
            sessionId = MDC.get("session_id") ?: "unknown"), Instant.now())
    }
}

@Component
class StoryTakedownLogListener(private val log: StructuredLogger) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun recorded(event: StoryTakenDownRecorded) {
        log.event("story_taken_down", "story_public_id" to event.storyId, "reason_code" to event.reasonCode,
            "previous_visibility" to event.previousVisibility, "failed_submission_public_id" to event.failedSubmissionId,
            "notified" to event.notified)
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun cleared(event: StoryTakedownCleared) {
        log.event("story_takedown_cleared", "story_public_id" to event.storyId, "submission_public_id" to event.submissionId,
            "visibility" to event.visibility)
    }
}
