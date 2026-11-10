package com.knk.manyak.story.event

import com.knk.manyak.story.draft.StoryDraftRepository
import com.knk.manyak.story.submission.StorySubmissionRepository
import com.knk.manyak.user.event.UserWithdrawnEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 탈퇴 회원의 제출본과 초안을 물리 삭제한다. 소유 스토리는 공개 상태를 유지하므로 지우지 않는다(팀 결정).
 *
 * MANDATORY로 탈퇴 트랜잭션에 참여하고 예외를 전파해 실패 시 탈퇴 전체를 롤백한다. push 정리(알림 내역 삭제)보다 먼저 돈다.
 * 제출본 다음 알림 내역 순서는 NOTIFICATION 4-1-19 "탈퇴와의 순서"가 적어 둔 순서이고, 검수 완료 알림 기록과 겹칠 때 회원 행
 * 잠금(MODERATION 5-2-1)에 이은 2차 방어다([UserWithdrawnEvent] 참조).
 */
@Component
class StoryWithdrawalCleanupListener(
    private val submissions: StorySubmissionRepository,
    private val drafts: StoryDraftRepository,
) {
    @EventListener
    @Order(1)
    @Transactional(propagation = Propagation.MANDATORY)
    fun onUserWithdrawn(event: UserWithdrawnEvent) {
        submissions.deleteForUser(event.userId)
        drafts.deleteForUser(event.userId)
    }
}
