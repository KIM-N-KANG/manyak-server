package com.knk.manyak.story.service

import com.knk.manyak.search.event.StoryIndexRequestedEvent
import com.knk.manyak.story.entity.StoryLike
import com.knk.manyak.story.repository.StoryLikeRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 유니크 위반은 호출자가 트랜잭션 밖에서 처리하고, 성공한 커밋만 색인한다. */
@Component
class StoryLikeWriter(private val likes: StoryLikeRepository, private val events: ApplicationEventPublisher) {
    @Transactional
    fun like(storyId: Long, userId: Long) {
        likes.saveAndFlush(StoryLike(userId = userId, storyId = storyId))
        events.publishEvent(StoryIndexRequestedEvent(storyId))
    }

    @Transactional
    fun unlike(storyId: Long, userId: Long) {
        likes.deleteByUserIdAndStoryId(userId, storyId)
        events.publishEvent(StoryIndexRequestedEvent(storyId))
    }
}
