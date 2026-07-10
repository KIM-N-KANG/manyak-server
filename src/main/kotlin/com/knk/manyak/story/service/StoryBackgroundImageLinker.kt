package com.knk.manyak.story.service

import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.image.service.RandomIndexPicker
import com.knk.manyak.story.entity.StoryImage
import com.knk.manyak.story.repository.StoryImageRepository
import org.springframework.stereotype.Component

/**
 * 스토리 등록 시 채팅 배경 후보를 확정해 연결한다(스펙 §4-3-9 배경 — 매 턴 AI 선택).
 *
 * 첫 번째 장르 태그와 일치하는 활성 배경 중 최대 [MAX_CANDIDATES]장을 뽑아 `story_images`에 고정하고,
 * 매 턴 같은 목록을 AI 요청에 싣는다. 후보를 등록 시 확정하는 이유: 백엔드는 이 턴의 장소·분위기를
 * 모르므로(의미 판단은 AI 몫) 턴마다 다시 골라도 결과가 같고, 매 턴 동일 목록이 프롬프트 prefix를
 * 안정시켜 캐싱에 유리하며 AI 무상태도 유지된다.
 *
 * 썸네일과 달리 **장르 무관 폴백이 없다.** 매칭이 0건이면 후보 없이 두고, 그 스토리의 채팅은
 * 이미지 없는 턴으로 진행한다 — 장면과 무관한 배경은 몰입을 해치기 때문이다.
 *
 * 실물 자산이 장르별로 얇아(6개 장르가 5장 미만) 최소 개수는 강제하지 않고 "있는 만큼" 연결한다.
 */
@Component
class StoryBackgroundImageLinker(
    private val imagePresetRepository: ImagePresetRepository,
    private val storyImageRepository: StoryImageRepository,
    private val randomIndexPicker: RandomIndexPicker,
) {
    fun linkFor(storyId: Long, genres: List<String>) {
        val firstGenre = genres.firstOrNull() ?: return
        val matched = imagePresetRepository
            .findActiveImageKeysByTypeAndGenreName(ImagePresetType.BACKGROUND, firstGenre)
            .toMutableList()
        if (matched.isEmpty()) {
            return
        }

        // 후보 풀에서 뽑은 키를 제거해 가며 고른다 — 같은 배경이 두 번 실리면 프롬프트만 길어진다.
        val picked = ArrayList<String>(minOf(matched.size, MAX_CANDIDATES))
        repeat(minOf(matched.size, MAX_CANDIDATES)) {
            picked += matched.removeAt(randomIndexPicker.pick(matched.size))
        }

        storyImageRepository.saveAll(picked.map { StoryImage(storyId = storyId, imageKey = it) })
    }

    companion object {
        const val MAX_CANDIDATES = 8
    }
}
