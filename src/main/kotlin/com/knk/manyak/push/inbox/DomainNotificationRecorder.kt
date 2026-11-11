package com.knk.manyak.push.inbox

import com.knk.manyak.story.event.StoryCompletedEvent
import com.knk.manyak.story.submission.StoryModerationCompleted
import com.knk.manyak.story.submission.StoryTakenDown
import com.knk.manyak.push.event.pushData
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 발송 모드나 푸시 자격과 무관한 동기 기록. 예외를 전파해 도메인의 완료 마킹도 롤백한다.
 *
 * 완성, 검수 종료, 공개 중단 이벤트를 발행 트랜잭션 안에서 구독한다(MANDATORY). [ORDER]로 같은 이벤트의 원격 아웃박스
 * 리스너보다 먼저 실행되어 내역 기록이 실패하면 아웃박스는 시도하지 않는다(NOTIFICATION 4-1-6, 4-1-7, 4-1-21).
 */
@Component
class DomainNotificationRecorder(
    private val store: UserNotificationStore,
    private val users: com.knk.manyak.auth.repository.UserRepository,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val base: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    @EventListener
    @Order(ORDER)
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

    @EventListener
    @Order(ORDER)
    @Transactional(propagation = Propagation.MANDATORY)
    fun takenDown(event: StoryTakenDown) {
        val type = NotificationType.STORY_TAKEN_DOWN
        val data = event.pushData(base)
        store.insert(UserNotification(userId = event.userId, messageKey = event.messageId,
            type = type, title = data.getValue("title"), body = data.getValue("body"),
            deepLink = inboxDeepLink(base, "/studio", type), storyPublicId = event.storyId,
            createdAt = clock.instant()))
    }

    @EventListener
    @Order(ORDER)
    @Transactional(propagation = Propagation.MANDATORY)
    fun moderated(event: StoryModerationCompleted) {
        val type = NotificationType.STORY_MODERATION_COMPLETED
        val data = event.pushData(base)
        store.insert(UserNotification(userId = event.userId, messageKey = "story-moderation:${event.submissionId}:${event.attempt}",
            type = type, title = data.getValue("title"), body = data.getValue("body"),
            deepLink = inboxDeepLink(base, "/studio", type), storyPublicId = event.storyId?.let(UUID::fromString),
            createdAt = clock.instant()))
    }

    companion object {
        /** 아웃박스 리스너(`@Order` 없음, 최저 우선순위)보다 앞선다. */
        const val ORDER = 0
    }
}
