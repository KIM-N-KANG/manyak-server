package com.knk.manyak.story.event

import com.knk.manyak.auth.event.UserNicknameChangedEvent
import com.knk.manyak.story.repository.StoryRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher

class StoryNicknameReindexListenerTests {
    private val stories = mock(StoryRepository::class.java)
    private val events = mock(ApplicationEventPublisher::class.java)

    @Test
    fun `닉네임이 바뀐 회원의 삭제되지 않은 소유 스토리 전체를 묶음 색인 요청 하나로 발행한다`() {
        `when`(stories.findActiveIdsByUserId(7)).thenReturn(listOf(11, 12))

        StoryNicknameReindexListener(stories, events).onNicknameChanged(UserNicknameChangedEvent(7))

        val order = inOrder(stories, events)
        order.verify(stories).findActiveIdsByUserId(7)
        order.verify(events).publishEvent(StoriesIndexRequestedEvent(listOf(11, 12)))
        verifyNoMoreInteractions(stories, events)
    }

    @Test
    fun `소유 스토리가 없으면 색인 요청을 발행하지 않는다`() {
        `when`(stories.findActiveIdsByUserId(7)).thenReturn(emptyList())

        StoryNicknameReindexListener(stories, events).onNicknameChanged(UserNicknameChangedEvent(7))

        verifyNoMoreInteractions(events)
    }
}
