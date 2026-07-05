package com.knk.manyak.story.service

import com.knk.manyak.story.dto.StoryPublicationResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 일반 모드 스토리 발행·공개 설정(KNK-402, AI 없음). 소유자만 발행·공개설정할 수 있다(무토큰 401, 타인 403).
 *
 * - 발행(publish): 초안(DRAFT)→PUBLISHED로 전환하며 요청한 공개 범위를 함께 설정한다. 제목이 있어야 발행할 수 있고,
 *   이미 발행된 스토리는 409다. 발행해도 visibility는 요청값 그대로이며 공개 피드 노출은 PUBLIC일 때만이다.
 * - 공개설정(updateVisibility): 스토리의 공개 범위를 바꾼다(공개↔비공개 토글). 발행과 분리한다.
 */
@Service
class StoryPublicationService(
    private val storyRepository: StoryRepository,
) {

    @Transactional
    fun publish(storyId: String, userId: Long, visibility: StoryVisibility): StoryPublicationResponse {
        val story = resolveOwnedStory(storyId, userId)
        // 발행은 초안에서만. 이미 발행됐으면 상태 충돌로 409.
        if (story.status != StoryStatus.DRAFT) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 발행된 스토리입니다.")
        }
        // 제목 없이는 발행할 수 없다(초안은 빈 제목으로 시작할 수 있다 — KNK-401).
        if (story.title.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "제목을 입력해야 발행할 수 있습니다.")
        }
        story.status = StoryStatus.PUBLISHED
        story.visibility = visibility
        // @Transactional 커밋 시 더티 체킹으로 반영된다(명시적 save 불필요).
        return story.toPublicationResponse()
    }

    @Transactional
    fun updateVisibility(storyId: String, userId: Long, visibility: StoryVisibility): StoryPublicationResponse {
        val story = resolveOwnedStory(storyId, userId)
        story.visibility = visibility
        return story.toPublicationResponse()
    }

    private fun Story.toPublicationResponse(): StoryPublicationResponse =
        StoryPublicationResponse(id = publicId.toString(), status = status, visibility = visibility)

    /**
     * 공개 식별자로 스토리를 조회하고 소유자를 검증한다. 형식 오류·미존재·삭제는 404, 소유자가 아니면 403.
     * 동시 상태 변경을 직렬화하려 쓰기 락으로 잡는다.
     */
    private fun resolveOwnedStory(publicId: String, userId: Long): Story {
        val parsed = try {
            UUID.fromString(publicId)
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        val story = storyRepository.findByPublicIdAndDeletedAtIsNullForUpdate(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        if (story.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        }
        return story
    }
}
