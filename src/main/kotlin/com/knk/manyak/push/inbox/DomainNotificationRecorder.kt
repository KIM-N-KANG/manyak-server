package com.knk.manyak.push.inbox

import com.knk.manyak.story.event.StoryCompletedEvent
import com.knk.manyak.story.submission.StoryModerationCompleted
import com.knk.manyak.story.submission.pushData
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** 모드·푸시 자격과 무관한 동기 기록. 예외를 전파해 도메인의 완료 마킹도 롤백한다. */
@Component
class DomainNotificationRecorder(
    private val store: UserNotificationStore,
    private val users: com.knk.manyak.auth.repository.UserRepository,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val base: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun completed(event: StoryCompletedEvent) {
        // 완료 기록이 먼저 잡은 사용자 잠금은 같은 트랜잭션에서 재사용한다. 탈퇴가 먼저 커밋됐으면 내역을 다시 만들지 않는다.
        val user = users.findByIdForUpdate(event.userId) ?: return
        if (user.status == com.knk.manyak.auth.entity.UserStatus.DELETED) return
        val type = NotificationType.STORY_COMPLETED
        store.insert(UserNotification(userId = event.userId, messageKey = "story-completed:${event.requestId}",
            type = type, title = "스토리가 완성됐어요", body = event.title,
            deepLink = inboxDeepLink(base, "/stories/${event.storyPublicId}", type),
            storyPublicId = UUID.fromString(event.storyPublicId), createdAt = clock.instant()))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun moderated(event: StoryModerationCompleted) {
        val type = NotificationType.STORY_MODERATION_COMPLETED
        val data = event.pushData(base)
        store.insert(UserNotification(userId = event.userId, messageKey = "story-moderation:${event.submissionId}:${event.attempt}",
            type = type, title = data.getValue("title"), body = data.getValue("body"),
            deepLink = inboxDeepLink(base, "/studio", type), storyPublicId = event.storyId?.let(UUID::fromString),
            createdAt = clock.instant()))
    }
}
