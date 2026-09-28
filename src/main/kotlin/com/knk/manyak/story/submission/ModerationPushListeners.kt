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
    val (notificationTitle, body) = when (status) {
        SubmissionStatus.APPROVED -> "검수를 통과했어요" to "「$title」의 등록이 완료됐어요. 지금 확인해 보세요."
        SubmissionStatus.REJECTED -> "검수에서 반려됐어요" to "「$title」의 내용을 수정해 다시 제출해 주세요."
        SubmissionStatus.FAILED -> "검수를 진행하지 못했어요" to "「$title」의 검수 중 문제가 생겼어요. 잠시 후 다시 제출해 주세요."
        SubmissionStatus.PENDING -> error("Pending submissions cannot publish a moderation completion push")
    }
    put("title", notificationTitle)
    put("body", body)
    put("type", "STORY_MODERATION_COMPLETED")
    put("submissionId", submissionId)
    put("status", status.name)
    storyId?.let { put("storyId", it) }
    put("deepLink", "${base.trimEnd('/')}/studio")
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
