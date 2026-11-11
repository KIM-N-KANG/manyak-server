package com.knk.manyak.story.event

import com.knk.manyak.auth.event.UserNicknameChangedEvent
import com.knk.manyak.story.repository.StoryRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 닉네임이 바뀐 회원의 소유 스토리를 다시 색인하도록 요청한다. 검색 문서의 `author`가 닉네임을 싣기 때문이다.
 *
 * MANDATORY로 프로필 수정 트랜잭션에 참여한다. 그래서 [StoryIndexRequestedEvent]의 AFTER_COMMIT 색인은 프로필이 커밋될 때만
 * 돌고, 대상 조회 예외는 전파돼 프로필 수정을 롤백한다.
 */
@Component
class StoryNicknameReindexListener(
    private val stories: StoryRepository,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onNicknameChanged(event: UserNicknameChangedEvent) {
        // 공개 전환과 경합하면 재색인이 누락될 수 있어 공개 여부로 거르지 않는다(Codex P2).
        val storyIds = stories.findActiveIdsByUserId(event.userId)
        storyIds.forEach { events.publishEvent(StoryIndexRequestedEvent(it)) }
        log.info("nickname_reindex_requested userId={} stories={}", event.userId, storyIds.size)
    }
}
