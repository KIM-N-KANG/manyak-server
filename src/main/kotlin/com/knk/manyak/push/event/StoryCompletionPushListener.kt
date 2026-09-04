package com.knk.manyak.push.event

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.story.event.StoryCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 스토리 완성 푸시(KNK-1115). 완성 마킹 트랜잭션이 커밋된 뒤 제작자에게 서비스 알림을 보낸다.
 *
 * - AFTER_COMMIT이라 생성이 롤백되면 보내지 않는다. 반대로 **발송 실패는 생성에 영향을 주지 않는다** —
 *   AFTER_COMMIT 콜백의 예외는 커밋을 되돌리지 못한 채 호출부로 전파되므로 여기서 삼키고 로그만 남긴다.
 * - 스토리 완성은 **서비스 알림**이다(사용자가 유발한 작업의 결과 통지). 광고 판정([canReceiveMarketingPush])이
 *   아니라 `servicePushEnabled`만 본다(KNK-1132, 정책 KNK-1129).
 * - 토큰이 없거나 정지·탈퇴 회원인 경우는 [FcmPushSender]가 조용히 건너뛴다(KNK-1130).
 *
 * ponytail: @Async를 붙이지 않았다. 완성 경로는 이미 AI 호출로 수 초~수십 초가 걸리는 동기 흐름이라 발송
 * 한 번이 체감에 묻히고, 동기로 두면 발송 여부를 테스트가 결정적으로 확인할 수 있다. 응답 지연이 문제가 되면
 * 그때 @Async(피드백 알림 선례)로 바꾼다.
 */
@Component
class StoryCompletionPushListener(
    private val userRepository: UserRepository,
    private val fcmPushSender: FcmPushSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 커밋 뒤라 진행 중인 트랜잭션이 없다. 사용자 조회를 위해 새 트랜잭션을 연다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStoryCompleted(event: StoryCompletedEvent) {
        try {
            val servicePushEnabled = userRepository.findById(event.userId).orElse(null)?.servicePushEnabled
            if (servicePushEnabled != true) {
                log.debug(
                    "서비스 알림 수신을 끈 회원이라 스토리 완성 푸시를 건너뜁니다. (userId={}, storyId={})",
                    event.userId, event.storyPublicId,
                )
                return
            }
            fcmPushSender.sendToUser(
                event.userId,
                mapOf(
                    "type" to STORY_COMPLETED_TYPE,
                    "storyId" to event.storyPublicId,
                    "title" to event.title,
                ),
            )
        } catch (ex: RuntimeException) {
            // 푸시는 부가 기능이고 진실의 원천은 복귀 조회(KNK-631)다. 여기서 던지면 이미 커밋된 생성의
            // 응답이 500으로 뒤집힌다.
            log.warn(
                "스토리 완성 푸시 발송에 실패했습니다. (userId={}, storyId={}, error={})",
                event.userId, event.storyPublicId, ex.javaClass.simpleName,
            )
        }
    }

    private companion object {
        /** 앱이 알림 UI를 조립할 때 쓰는 시나리오 식별자(data 전용 메시지 — KNK-1130). */
        const val STORY_COMPLETED_TYPE = "STORY_COMPLETED"
    }
}
