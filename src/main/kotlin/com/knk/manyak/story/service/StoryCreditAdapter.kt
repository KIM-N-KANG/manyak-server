package com.knk.manyak.story.service

import com.knk.manyak.credit.port.StoryCreationProgress
import com.knk.manyak.credit.port.StoryTitleLookup
import com.knk.manyak.credit.port.StoryTitles
import com.knk.manyak.story.dto.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.stereotype.Component

/** 크레딧 포트의 story 쪽 구현(KNK-2066). `@Transactional`을 달지 않아 호출자의 트랜잭션 경계를 그대로 쓴다. */
@Component
class StoryCreditAdapter(
    private val sessions: StoryCreationSessionRepository,
    private val stories: StoryRepository,
    private val snapshots: StoryPublicSnapshotService,
) : StoryCreationProgress, StoryTitleLookup {

    override fun isStoryCreated(sessionId: Long): Boolean? =
        sessions.findById(sessionId).map { it.status == StoryCreationSessionStatus.STORY_CREATED }.orElse(null)

    /**
     * 제작 참조의 `ref_id`는 스토리 PK가 아니라 **제작 세션 PK**라 세션을 한 단계 거친다. 실패한 세션은 `story_id`가
     * NULL이라 제목도 없다.
     *
     * 채팅 참조는 서재와 같은 스냅샷 규칙을 탄다(KNK-1059): 요청자가 지금 그 스토리를 읽을 수 있으면 현재 제목,
     * 아니면 마지막 공개 스냅샷의 제목(KNK-1065). 제작 참조는 요청자가 곧 소유자라 읽기가 언제나 허용되므로 새는
     * 정보가 없고, 삭제된 스토리면 제목을 내리지 않는다(클라이언트가 "삭제된 스토리" 폴백 문구를 쓴다).
     */
    override fun titles(userId: Long, chatStoryIds: Collection<Long>, sessionIds: Collection<Long>): StoryTitles {
        val storyIdBySession = sessions.findAllById(sessionIds).associate { it.id to it.storyId }
        val storyIds = (chatStoryIds + storyIdBySession.values.filterNotNull()).toSet()
        val storyById = stories.findAllById(storyIds).associateBy { it.id }
        // 스냅샷은 읽을 수 없는 스토리에만 필요하다(별도 테이블, PR #224 Codex P2). 그 스토리들만 한 번에 조회한다.
        val snapshotByStoryId = snapshots.findAllByStoryIds(
            storyById.values.filterNot { it.isCurrentMetadataVisibleTo(userId) }.map { it.id },
        )

        val byChatStoryId = chatStoryIds.toSet().mapNotNull { storyId ->
            val story = storyById[storyId]
            val title = if (story?.isCurrentMetadataVisibleTo(userId) == true) {
                UsernameTokenRenderer.render(story.title, story.protagonistName)
            } else {
                story?.let { snapshotByStoryId[it.id] }?.let { UsernameTokenRenderer.render(it.title, it.protagonistName) }
            } ?: return@mapNotNull null
            storyId to title
        }.toMap()
        val bySessionId = storyIdBySession.mapNotNull { (sessionId, storyId) ->
            val title = storyId
                ?.let { storyById[it] }
                ?.takeIf { it.deletedAt == null }
                ?.let { UsernameTokenRenderer.render(it.title, it.protagonistName) }
                ?: return@mapNotNull null
            sessionId to title
        }.toMap()
        return StoryTitles(byChatStoryId, bySessionId)
    }
}
