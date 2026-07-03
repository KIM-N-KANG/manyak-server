package com.knk.manyak.story.service

import com.knk.manyak.story.dto.StorylineRating
import com.knk.manyak.story.dto.StorylineRatingResponse
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationStorylineRating
import com.knk.manyak.story.repository.StoryCreationStorylineRatingRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class StorylineRatingService(
    private val storyCreationStorylineRepository: StoryCreationStorylineRepository,
    private val storyCreationStorylineRatingRepository: StoryCreationStorylineRatingRepository,
) {

    // 스토리라인 평가는 대상당 1개다. 같은 스토리라인을 다시 평가하면 값만 갱신한다(upsert).
    //
    // 소유권 강제(Codex PR #76 P2): 스토리라인은 생성한 세션의 소유자만 보고 평가하므로, 소유 세션이면
    // 같은 사용자만 평가/취소할 수 있다. 다른 사용자/익명이 storylineId로 남의 스토리라인을 평가·취소하는 것을 막는다.
    // (평가 주체 user_id 컬럼은 없다 — V9 주석. 귀속을 저장하진 않고 접근 제어만 한다.)
    @Transactional
    fun rate(storylineId: Long, rating: StorylineRating, userId: Long? = null): StorylineRatingResponse {
        loadStorylineEnforcingOwner(storylineId, userId)

        val existing = storyCreationStorylineRatingRepository.findByStorylineId(storylineId)
        if (existing != null) {
            // 영속 상태 엔티티이므로 변경 감지(dirty checking)로 커밋 시 자동 반영된다. save() 불필요.
            existing.rating = rating
        } else {
            storyCreationStorylineRatingRepository.save(
                StoryCreationStorylineRating(storylineId = storylineId, rating = rating),
            )
        }

        return StorylineRatingResponse(id = storylineId, rating = rating)
    }

    // 취소는 행을 물리 삭제한다. 평가가 없는 스토리라인이면 멱등하게 통과한다.
    @Transactional
    fun cancel(storylineId: Long, userId: Long? = null) {
        loadStorylineEnforcingOwner(storylineId, userId)
        storyCreationStorylineRatingRepository.deleteByStorylineId(storylineId)
    }

    /**
     * 스토리라인을 로드하며 소유권을 강제한다. 없으면 404, 소유 세션(소유자 있음)인데 요청자가 다르거나 익명이면 403.
     * 익명 세션(소유자 없음)은 누구나 허용한다.
     */
    private fun loadStorylineEnforcingOwner(storylineId: Long, userId: Long?): StoryCreationStoryline {
        val storyline = storyCreationStorylineRepository.findById(storylineId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "스토리라인을 찾을 수 없습니다.") }
        val ownerId = storyline.creationSession.userId
        if (ownerId != null && ownerId != userId) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "본인이 시작한 간편 제작의 스토리라인만 평가할 수 있습니다.",
            )
        }
        return storyline
    }
}
