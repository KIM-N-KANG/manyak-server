package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.ChatTurnBackgroundImage
import com.knk.manyak.chat.client.ChatTurnCharacterImage
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import org.springframework.stereotype.Component

/**
 * 매 턴 AI 요청에 실을 이미지 재료를 모은다(스펙 §4-3-9).
 *
 * 배경은 등록 시 확정한 후보 목록을 매 턴 **같은 순서로** 싣는다 — 프롬프트 prefix가 안정되어 캐싱에 유리하고
 * AI 무상태도 유지된다. 캐릭터는 컴파일이 확정한 인물↔이미지 매핑을 그대로 되돌려 싣는다(AI는 선택하지 않음).
 *
 * 비활성 이미지는 양쪽 모두 전달에서 제외한다. 내린 다음 턴부터 즉시 노출이 멈춰야 하고, 인물 이미지가
 * 비활성이면 그 인물은 이미지 없이 진행한다.
 */
@Component
class ChatImageMaterialProvider(
    private val imagePresetRepository: ImagePresetRepository,
    private val storyCharacterRepository: StoryCharacterRepository,
) {
    fun backgroundCandidates(storyId: Long): List<ChatTurnBackgroundImage> =
        imagePresetRepository.findActiveBackgroundCandidates(storyId).map {
            // subject 축의 뜻은 타입마다 다르다 — 배경에서는 장소다.
            ChatTurnBackgroundImage(imageKey = it.imageKey, mood = it.mood, place = it.subject, prop = it.prop)
        }

    fun characterImages(storyId: Long): List<ChatTurnCharacterImage> =
        storyCharacterRepository.findWithActiveImageByStoryId(storyId).mapNotNull { character ->
            character.imageKey?.let { ChatTurnCharacterImage(name = character.name, imageKey = it) }
        }
}
