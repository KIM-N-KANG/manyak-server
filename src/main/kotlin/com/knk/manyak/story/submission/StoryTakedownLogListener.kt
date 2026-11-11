package com.knk.manyak.story.submission

import com.knk.manyak.global.observability.StructuredLogger
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class StoryTakedownLogListener(private val log: StructuredLogger) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun recorded(event: StoryTakenDownRecorded) {
        log.event("story_taken_down", "story_public_id" to event.storyId, "reason_code" to event.reasonCode,
            "previous_visibility" to event.previousVisibility, "failed_submission_public_id" to event.failedSubmissionId,
            "notified" to event.notified)
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun cleared(event: StoryTakedownCleared) {
        log.event("story_takedown_cleared", "story_public_id" to event.storyId, "submission_public_id" to event.submissionId,
            "visibility" to event.visibility)
    }
}
