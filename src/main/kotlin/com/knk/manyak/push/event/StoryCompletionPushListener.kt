package com.knk.manyak.push.event

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.config.PushAsyncConfig
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.story.event.StoryCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskRejectedException
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.Executor

/** local 모드의 커밋 후 발송. remote는 동기 아웃박스 리스너가 맡는다. */
@Component
class StoryCompletionPushListener(
    private val userRepository: UserRepository,
    private val fcmPushSender: FcmPushSender,
    @Qualifier(PushAsyncConfig.PUSH_EXECUTOR)
    private val pushExecutor: Executor,
    @Value("\${manyak.push.mode:local}")
    private val pushMode: String = PUSH_MODE_LOCAL,
    @Value("\${manyak.push.web-base-url:https://manyak.app}")
    private val webBaseUrl: String = "https://manyak.app",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStoryCompleted(event: StoryCompletedEvent) {
        if (pushMode.equals(PUSH_MODE_REMOTE, ignoreCase = true)) return
        // 제출은 요청 스레드에서 일어난다. MDC가 아직 살아 있어야 PushAsyncConfig의 decorator가 워커로 옮긴다.
        try {
            pushExecutor.execute { dispatch(event) }
        } catch (ex: TaskRejectedException) {
            // 큐가 가득 찼다. 이 푸시는 유실된다 — AFTER_COMMIT 이벤트는 다시 발행되지 않는다.
            // 삼키지 않으면 이미 커밋된 스토리 생성의 응답이 500으로 뒤집힌다.
            log.warn(
                "푸시 실행기 포화로 스토리 완성 푸시를 버립니다. (userId={}, storyId={})",
                event.userId, event.storyPublicId,
            )
        }
    }

    // 트랜잭션을 열지 않는다 — 조회 한 번과 발송뿐이라 Spring Data가 여는 트랜잭션으로 충분하다.
    private fun dispatch(event: StoryCompletedEvent) {
        try {
            // local 발송 직전에 서비스 알림 동의를 확인한다.
            val user = userRepository.findById(event.userId).orElse(null)
            if (user?.servicePushEnabled != true) {
                log.debug(
                    "서비스 알림 수신을 끈 회원이라 스토리 완성 푸시를 건너뜁니다. (userId={}, storyId={})",
                    event.userId, event.storyPublicId,
                )
                return
            }
            val data = mapOf(
                "type" to STORY_COMPLETED_TYPE,
                "storyId" to event.storyPublicId,
                "title" to event.title,
                "deepLink" to "${webBaseUrl.trimEnd('/')}/stories/${event.storyPublicId}",
            )
            fcmPushSender.sendToUser(event.userId, data)
        } catch (ex: RuntimeException) {
            // 푸시는 부가 기능이고 진실의 원천은 복귀 조회(KNK-631)다. 워커 스레드라 요청에 전파되지는
            // 않지만, 삼키지 않으면 스택트레이스만 남고 어느 회원의 발송이 깨졌는지 알 수 없다.
            log.warn(
                "스토리 완성 푸시 발송에 실패했습니다. (userId={}, storyId={}, error={})",
                event.userId, event.storyPublicId, ex.javaClass.simpleName,
            )
        }
    }

    private companion object {
        /** 앱이 알림 UI를 조립할 때 쓰는 시나리오 식별자(data 전용 메시지 — KNK-1130). */
        const val STORY_COMPLETED_TYPE = "STORY_COMPLETED"

        /** 서버 안의 발송기로 보낸다(기본값). */
        const val PUSH_MODE_LOCAL = "local"

        /** 알림 서비스가 보낸다(KNK-1375). */
        const val PUSH_MODE_REMOTE = "remote"
    }
}
