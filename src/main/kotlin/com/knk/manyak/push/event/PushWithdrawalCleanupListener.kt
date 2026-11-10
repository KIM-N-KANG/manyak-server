package com.knk.manyak.push.event

import com.knk.manyak.push.inbox.UserNotificationStore
import com.knk.manyak.push.repository.DevicePushTokenRepository
import com.knk.manyak.user.event.UserWithdrawnEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 탈퇴 회원의 디바이스 푸시 토큰과 알림 내역을 물리 삭제한다(KNK-1131). 탈퇴한 회원의 기기로 알림이 가면 안 되고,
 * 토큰은 재가입 매칭에 쓰이지 않아 남길 이유가 없다(재가입 기기는 앱이 새로 등록한다).
 *
 * MANDATORY로 탈퇴 트랜잭션에 참여하고 예외를 전파해 실패 시 탈퇴 전체를 롤백한다. story 정리(제출본 삭제) 다음에 돈다.
 * 제출본 다음 알림 내역 순서는 NOTIFICATION 4-1-19 "탈퇴와의 순서"가 적어 둔 순서이고, 검수 완료 알림 기록과 겹칠 때 회원 행
 * 잠금(MODERATION 5-2-1)에 이은 2차 방어다([UserWithdrawnEvent] 참조).
 *
 * 주의: [DevicePushTokenRepository.deleteByUserId]는 `flushAutomatically`·`clearAutomatically`라 탈퇴가 그때까지
 * 바꾼 사용자·소셜 엔티티를 flush한 뒤 영속성 컨텍스트를 비운다. 그래서 발행자는 엔티티 수정을 모두 발행 앞에서 끝낸다.
 */
@Component
class PushWithdrawalCleanupListener(
    private val devicePushTokens: DevicePushTokenRepository,
    private val notifications: UserNotificationStore,
) {
    @EventListener
    @Order(2)
    @Transactional(propagation = Propagation.MANDATORY)
    fun onUserWithdrawn(event: UserWithdrawnEvent) {
        devicePushTokens.deleteByUserId(event.userId)
        notifications.deleteForUser(event.userId)
    }
}
