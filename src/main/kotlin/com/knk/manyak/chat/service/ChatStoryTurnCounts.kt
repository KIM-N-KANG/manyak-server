package com.knk.manyak.chat.service

import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.service.StoryTurnCounts
import org.springframework.stereotype.Component

/** [StoryTurnCounts]의 chat 쪽 구현. 집계식은 [StoryChatRepository]가 소유한다. */
@Component
class ChatStoryTurnCounts(
    private val storyChatRepository: StoryChatRepository,
) : StoryTurnCounts {

    override fun countByStoryId(storyId: Long): Long = storyChatRepository.sumTurnCountByStoryId(storyId)

    override fun countByStoryIds(storyIds: Collection<Long>): Map<Long, Long> =
        storyChatRepository.sumTurnCountByStoryIds(storyIds).associate { it.storyId to it.turnCount }
}
