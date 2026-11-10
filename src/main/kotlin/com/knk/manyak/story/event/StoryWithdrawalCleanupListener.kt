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
 * MANDATORY로 탈퇴 트랜잭션에 참여하고 예외를 전파해 실패 시 탈퇴 전체를 롤백한다. 순서는 push 정리보다 먼저다
 * ([UserWithdrawnEvent] 참조).
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
