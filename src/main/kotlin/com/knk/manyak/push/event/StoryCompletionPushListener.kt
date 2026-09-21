package com.knk.manyak.push.event

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.client.NotificationClient
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.story.event.StoryCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 스토리 완성 푸시(KNK-1115). 완성 마킹 트랜잭션이 커밋된 뒤 제작자에게 서비스 알림을 보낸다.
 *
 * - AFTER_COMMIT이라 생성이 롤백되면 보내지 않는다. 반대로 **발송 실패는 생성에 영향을 주지 않는다** —
 *   AFTER_COMMIT 콜백의 예외는 커밋을 되돌리지 못한 채 호출부로 전파되므로 여기서 삼키고 로그만 남긴다.
 * - 스토리 완성은 **서비스 알림**이다(사용자가 유발한 작업의 결과 통지). 광고 판정([canReceiveMarketingPush])이
 *   아니라 `servicePushEnabled`만 본다(KNK-1132, 정책 KNK-1129).
 * - 토큰이 없거나 정지·탈퇴 회원인 경우는 발송 실행 주체가 조용히 건너뛴다(KNK-1130).
 * - **발송 실행 위치는 `manyak.push.mode`가 가른다**(KNK-1375). `local`은 서버 안의 [FcmPushSender],
 *   `remote`는 알림 서비스([NotificationClient])다. 어느 쪽이든 **무엇을 보낼지와 보내도 되는지는 서버가
 *   판단한다** — 아래 수신 동의 확인은 모드와 무관하게 그대로 돈다. 기본값 `local`이라 배포만으로는
 *   경로가 바뀌지 않고, 환경변수 하나로 켜고 같은 값으로 되돌린다.
 * - **@Async로 요청 스레드와 분리한다**(피드백 알림 선례, Codex 리뷰 P1). AFTER_COMMIT 콜백은 원 트랜잭션의
 *   커넥션이 반납되기 전에 돌아, 여기서 DB를 읽으면 요청 하나가 커넥션 두 개를 동시에 쥔다. 풀이 포화되면
 *   커넥션 획득이 `connectionTimeout`으로 실패하고, 그 실패는 아래 try 바깥(트랜잭션 시작 시점)이라 잡히지도
 *   않아 이미 커밋된 생성의 응답이 500으로 뒤집힌다. 조회(회원)와 발송(토큰) 둘 다 DB를 타므로 접근을 없앨
 *   수는 없고, 스레드를 분리해 원 커넥션이 반납된 뒤에 읽는다.
 */
@Component
class StoryCompletionPushListener(
    private val userRepository: UserRepository,
    private val fcmPushSender: FcmPushSender,
    private val notificationClient: NotificationClient,
    @Value("\${manyak.push.mode:local}")
    private val pushMode: String = PUSH_MODE_LOCAL,
    @Value("\${manyak.push.web-base-url:https://manyak.app}")
    private val webBaseUrl: String = "https://manyak.app",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 트랜잭션을 열지 않는다 — 조회 한 번과 발송뿐이라 Spring Data가 여는 트랜잭션으로 충분하다.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStoryCompleted(event: StoryCompletedEvent) {
        try {
            // remote 모드는 수신자를 public_id로 넘기므로 회원 엔티티가 필요하다. 동의 확인은 두 모드가 공유한다.
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
            if (pushMode.equals(PUSH_MODE_REMOTE, ignoreCase = true)) {
                notificationClient.send(user.publicId, PushKind.SERVICE, STORY_COMPLETED_TYPE, data)
            } else {
                fcmPushSender.sendToUser(event.userId, data)
            }
        } catch (ex: RuntimeException) {
            // 푸시는 부가 기능이고 진실의 원천은 복귀 조회(KNK-631)다. @Async 스레드라 요청에 전파되지는
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
