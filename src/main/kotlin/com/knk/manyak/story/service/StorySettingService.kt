package com.knk.manyak.story.service

import com.knk.manyak.story.dto.StorySettingResponse
import com.knk.manyak.story.dto.UpdateStorySettingRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 세계관 설정(StorySetting)의 조회·초안 저장(KNK-401). 초안은 비공개라 **소유자만** 접근한다(무토큰 401, 타인 403).
 *
 * 저장은 PATCH 의미다: 요청에서 non-null인 필드만 갱신하고 미제공(null) 필드는 기존 값을 유지한다.
 * 세계관·인물 등 여러 탭이 같은 StorySetting 행을 부분적으로 자동저장하기 위함이다. 처음 저장이면 StorySetting을 생성한다.
 */
@Service
class StorySettingService(
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
) {

    @Transactional(readOnly = true)
    fun getSetting(storyId: String, userId: Long): StorySettingResponse {
        val story = resolveOwnedStory(storyId, userId)
        return storySettingRepository.findByStoryId(story.id).toResponse()
    }

    @Transactional
    fun upsertSetting(storyId: String, userId: Long, request: UpdateStorySettingRequest): StorySettingResponse {
        val story = resolveOwnedStory(storyId, userId, forUpdate = true)
        val setting = storySettingRepository.findByStoryId(story.id) ?: StorySetting(story = story)
        // PATCH 의미: non-null 필드만 갱신하고 null은 기존 값을 유지한다(탭별 부분 자동저장).
        request.worldSetting?.let { setting.worldSetting = it }
        request.characterSetting?.let { setting.characterSetting = it }
        request.userRoleSetting?.let { setting.userRoleSetting = it }
        request.ruleSetting?.let { setting.ruleSetting = it }
        return storySettingRepository.save(setting).toResponse()
    }

    private fun StorySetting?.toResponse(): StorySettingResponse =
        StorySettingResponse(
            worldSetting = this?.worldSetting,
            characterSetting = this?.characterSetting,
            userRoleSetting = this?.userRoleSetting,
            ruleSetting = this?.ruleSetting,
        )

    /**
     * 공개 식별자로 스토리를 조회하고 소유자를 검증한다. 형식 오류·미존재·삭제는 404, 소유자가 아니면 403.
     */
    private fun resolveOwnedStory(publicId: String, userId: Long, forUpdate: Boolean = false): Story {
        val parsed = try {
            UUID.fromString(publicId)
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        val story = (
            if (forUpdate) {
                storyRepository.findByPublicIdAndDeletedAtIsNullForUpdate(parsed)
            } else {
                storyRepository.findByPublicIdAndDeletedAtIsNull(parsed)
            }
            ) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        if (story.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        }
        return story
    }
}
