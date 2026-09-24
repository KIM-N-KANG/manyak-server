package com.knk.manyak.push.outbox

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.event.StoryCompletedEvent
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 완료 상태와 알림 요청을 원자적으로 기록한다. AFTER_COMMIT이나 비동기 수신으로 바꾸면
 * 아웃박스 기록 실패 시 COMPLETED만 남아 복구 요청에서도 알림을 만들 수 없게 된다.
 */
@Component
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class StoryCompletionOutboxListener(
    private val users: UserRepository,
    private val store: PushOutboxStore,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val webBaseUrl: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    // MANDATORY로 완료 마킹 트랜잭션에 참여하고 예외를 전파해 기록 실패 시 COMPLETED도 롤백한다.
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onStoryCompleted(event: StoryCompletedEvent) {
        val user = users.findById(event.userId).orElseThrow()
        store.insert(PushMessage(
            messageId = "story-completed:${event.requestId}",
            recipientId = user.publicId.toString(),
            data = mapOf("type" to "STORY_COMPLETED", "storyId" to event.storyPublicId, "title" to event.title,
                "deepLink" to "${webBaseUrl.trimEnd('/')}/stories/${event.storyPublicId}"),
            requestId = MDC.get("request_id") ?: "unknown",
            sessionId = MDC.get("session_id") ?: "unknown",
        ), clock.instant())
    }
}
