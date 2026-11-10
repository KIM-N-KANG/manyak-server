package com.knk.manyak.story.service

import com.knk.manyak.credit.port.StoryCreationProgress
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import org.springframework.stereotype.Component

/** 크레딧 포트의 story 쪽 구현(KNK-2066). `@Transactional`을 달지 않아 호출자의 트랜잭션 경계를 그대로 쓴다. */
@Component
class StoryCreditAdapter(
    private val sessions: StoryCreationSessionRepository,
) : StoryCreationProgress {

    override fun isStoryCreated(sessionId: Long): Boolean? =
        sessions.findById(sessionId).map { it.status == StoryCreationSessionStatus.STORY_CREATED }.orElse(null)
}
