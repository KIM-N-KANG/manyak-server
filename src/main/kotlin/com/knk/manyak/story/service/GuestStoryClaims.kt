package com.knk.manyak.story.service

import com.knk.manyak.story.dto.GuestStoryCandidate
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 게스트 이관의 스토리 쪽 조회와 클레임(ACCOUNT 5-2-2, KNK-2080). 판정 순서와 기기 증명 규칙은 이관이 가지고, 이 클래스는
 * 스토리와 진행의 소유자를 조건부로 바꾸는 명령과 판정 재료만 준다. 이관의 회원 행 잠금 트랜잭션 안에서 부른다.
 */
@Component
class GuestStoryClaims(
    private val stories: StoryRepository,
    private val sessions: StoryCreationSessionRepository,
    private val requests: StoryCreationRequestRepository,
) {
    /** 삭제되지 않은 스토리만 담는다. 소유자 없는 스토리가 있을 때만 진행과 완성 요청을 묶음으로 읽는다. */
    fun candidates(publicIds: Set<UUID>): Map<UUID, GuestStoryCandidate> {
        val rows = stories.findAllByPublicIdInAndDeletedAtIsNull(publicIds)
        val guestStoryIds = rows.filter { it.userId == null }.map { it.id }.toSet()
        val deviceHashesByStoryId = if (guestStoryIds.isEmpty()) emptyMap() else {
            val storySessions = sessions.findAllByStoryIdIn(guestStoryIds)
            val requestIds = storySessions.mapNotNull { it.creationRequestId }.toSet()
            val requestsById = if (requestIds.isEmpty()) emptyMap() else
                requests.findAllByRequestIdIn(requestIds).associateBy { it.requestId }
            storySessions.mapNotNull { session ->
                val storyId = session.storyId ?: return@mapNotNull null
                val hash = requestsById[session.creationRequestId]?.deviceIdHash?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                storyId to hash
            }.groupBy({ it.first }, { it.second })
        }
        return rows.associate { story ->
            story.publicId to GuestStoryCandidate(
                storyId = story.id,
                ownerUserId = story.userId,
                publiclyListed = story.isPubliclyListed(),
                recordedDeviceHashes = deviceHashesByStoryId[story.id].orEmpty(),
            )
        }
    }

    /**
     * 소유자가 비어 있을 때만 [userId]로 바꾸는 조건부 갱신. 바꿨으면 연결된 진행의 소유자도 같은 조건으로 맞추고 true.
     * 진행 소유자가 비어 남으면 스토리라인 평가와 취소가 익명 취급으로 열린다. 0행이면 아무것도 하지 않고 false.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun claim(publicId: UUID, storyId: Long, userId: Long): Boolean {
        if (stories.claimByPublicId(publicId, userId) != 1) return false
        sessions.claimByStoryId(storyId, userId)
        return true
    }

    /** 조건부 갱신이 0행일 때 실제 소유자를 다시 읽는다. */
    fun ownerOf(publicId: UUID): Long? = stories.findUserIdByPublicId(publicId)
}
