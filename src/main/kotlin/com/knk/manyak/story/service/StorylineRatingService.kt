package com.knk.manyak.story.service

import com.knk.manyak.story.dto.StorylineRating
import com.knk.manyak.story.dto.StorylineRatingResponse
import com.knk.manyak.story.entity.StoryCreationExampleRating
import com.knk.manyak.story.repository.StoryCreationExampleRatingRepository
import com.knk.manyak.story.repository.StoryCreationExampleRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class StorylineRatingService(
    private val storyCreationExampleRepository: StoryCreationExampleRepository,
    private val storyCreationExampleRatingRepository: StoryCreationExampleRatingRepository,
) {

    // 스토리라인 평가는 대상당 1개다. 같은 스토리라인을 다시 평가하면 값만 갱신한다(upsert).
    @Transactional
    fun rate(storylineId: Long, rating: StorylineRating): StorylineRatingResponse {
        val existing = storyCreationExampleRatingRepository.findByExampleId(storylineId)
        if (existing != null) {
            // 영속 상태 엔티티이므로 변경 감지(dirty checking)로 커밋 시 자동 반영된다. save() 불필요.
            // 평가가 존재한다는 것은 FK 제약상 부모 스토리라인 존재가 보장되므로 별도 검사도 생략한다.
            existing.rating = rating
        } else {
            requireStorylineExists(storylineId)
            storyCreationExampleRatingRepository.save(
                StoryCreationExampleRating(exampleId = storylineId, rating = rating),
            )
        }

        return StorylineRatingResponse(id = storylineId, rating = rating)
    }

    // 취소는 행을 물리 삭제한다. 평가가 없는 스토리라인이면 멱등하게 통과한다.
    @Transactional
    fun cancel(storylineId: Long) {
        requireStorylineExists(storylineId)
        storyCreationExampleRatingRepository.deleteByExampleId(storylineId)
    }

    private fun requireStorylineExists(storylineId: Long) {
        if (!storyCreationExampleRepository.existsById(storylineId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리라인을 찾을 수 없습니다.")
        }
    }
}
