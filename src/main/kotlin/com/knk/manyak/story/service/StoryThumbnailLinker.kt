package com.knk.manyak.story.service

import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.image.service.RandomIndexPicker
import org.springframework.stereotype.Component

/**
 * 스토리 등록 시 대표 이미지(표지)를 팀 이미지 중에서 자동 연결한다(스펙 §4-3-9 썸네일 자동 연결).
 *
 * 첫 번째 장르 태그와 일치하는 썸네일 중 랜덤 1개 → 없으면 장르 무관 썸네일 중 랜덤 1개 → 하나도 없으면 null.
 * 연결은 등록 시 1회 확정이며, 이후 수정으로 장르가 바뀌어도 재연결하지 않는다.
 */
@Component
class StoryThumbnailLinker(
    private val imagePresetRepository: ImagePresetRepository,
    private val randomIndexPicker: RandomIndexPicker,
) {
    fun linkFor(genres: List<String>): String? {
        val byGenre = genres.firstOrNull()
            ?.let { imagePresetRepository.findActiveImageKeysByTypeAndGenreName(ImagePresetType.THUMBNAIL, it) }
            .orEmpty()
        val candidates = byGenre.ifEmpty {
            imagePresetRepository.findActiveImageKeysByType(ImagePresetType.THUMBNAIL)
        }
        if (candidates.isEmpty()) {
            return null
        }
        return candidates[randomIndexPicker.pick(candidates.size)]
    }
}
