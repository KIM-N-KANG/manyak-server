package com.knk.manyak.story.service

import com.knk.manyak.search.event.StoryIndexRequestedEvent
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryTakedownReason
import com.knk.manyak.story.entity.StoryVisibility
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 호출자가 회원 → 스토리 잠금을 확보한 상태에서만 적용한다. */
@Service
class StoryTakedownStateService(private val snapshots: StoryPublicSnapshotService, private val events: ApplicationEventPublisher) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(story: Story, reason: StoryTakedownReason, detail: String?, now: Instant) {
        if (story.isPubliclyVisible()) snapshots.refresh(story)
        story.visibility = StoryVisibility.PRIVATE
        story.takenDownAt = now
        story.takedownReason = reason
        story.takedownReasonDetail = detail
        events.publishEvent(StoryIndexRequestedEvent(story.id))
    }

    /** 승인 적용 전에 지운다. 이후 적용 실패는 같은 트랜잭션의 표시 해제도 롤백한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun clear(story: Story): Boolean {
        if (story.takenDownAt == null) return false
        story.takenDownAt = null
        story.takedownReason = null
        story.takedownReasonDetail = null
        return true
    }
}
