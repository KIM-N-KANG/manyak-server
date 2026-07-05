package com.knk.manyak.story.service

import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.UpdateStartSettingRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 일반 모드 시작설정(StoryStartSetting) 조회·저작(KNK-460, AI 없음). 초안은 비공개라 **소유자만** 접근한다(무토큰 401, 타인 403).
 *
 * 저장은 PATCH 의미다: 요청에서 non-null인 필드만 갱신하고 미제공(null) 필드는 기존 값을 유지한다(탭별 부분 자동저장).
 * 처음 저장이면 StoryStartSetting을 생성한다(스토리당 1:1). 세계관 저작(KNK-401)과 동일한 패턴이다.
 */
@Service
class StoryStartSettingService(
    private val storyRepository: StoryRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
) {

    @Transactional(readOnly = true)
    fun getStartSetting(storyId: String, userId: Long): StoryStartSettingResponse {
        val story = resolveOwnedStory(storyId, userId)
        return storyStartSettingRepository.findByStoryId(story.id).toResponse()
    }

    @Transactional
    fun upsertStartSetting(storyId: String, userId: Long, request: UpdateStartSettingRequest): StoryStartSettingResponse {
        val story = resolveOwnedStory(storyId, userId, forUpdate = true)
        val setting = storyStartSettingRepository.findByStoryId(story.id) ?: StoryStartSetting(story = story)
        // PATCH 의미: non-null 필드만 갱신하고 null은 기존 값을 유지한다.
        request.name?.let { setting.name = it }
        request.prologue?.let { setting.prologue = it }
        request.startSituation?.let { setting.startSituation = it }
        request.openingScene?.let { setting.openingScene = it }
        request.firstAiMessage?.let { setting.firstAiMessage = it }
        return storyStartSettingRepository.save(setting).toResponse()
    }

    // 시작설정이 아직 없으면 빈 응답(name은 빈 문자열, 나머지 null)을 돌려준다.
    private fun StoryStartSetting?.toResponse(): StoryStartSettingResponse =
        StoryStartSettingResponse(
            name = this?.name ?: "",
            prologue = this?.prologue,
            startSituation = this?.startSituation,
            openingScene = this?.openingScene,
            firstAiMessage = this?.firstAiMessage,
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
