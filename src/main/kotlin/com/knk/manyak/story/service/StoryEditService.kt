package com.knk.manyak.story.service

import com.knk.manyak.story.dto.StoryEditFormResponse
import com.knk.manyak.story.dto.StoryEditSettingsResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.dto.toEndingResponse
import com.knk.manyak.story.dto.toMainEventResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 스토리 수정(스펙 §4-3-8, KNK-404): 수정 폼 조회(GET /stories/{id}/edit)와 부분 갱신(PATCH /stories/{id}).
 *
 * 소유권(§4-5): user_id가 NULL인 게스트 스토리는 익명 허용(현행 유지), 회원 소유 스토리는 소유자만 접근하고
 * 불일치·미인증이면 403이다. 간편·일반 제작 방식과 무관하게 같은 계약으로 수정한다. 이미지는 §4-3-9 범위라 제외한다.
 */
@Service
class StoryEditService(
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
) {

    @Transactional(readOnly = true)
    fun getEditForm(storyId: String, userId: Long?): StoryEditFormResponse {
        val story = resolveStory(storyId)
        requireOwnerOrAnonymous(story, userId)
        return buildEditForm(story)
    }

    /** 부분 갱신: 보낸(non-null) 필드만 교체하고 나머지는 유지한다. 리스트는 보내면 전체 교체다. */
    @Transactional
    fun updateStory(storyId: String, userId: Long?, request: UpdateStoryRequest): StoryEditFormResponse {
        val story = resolveStory(storyId)
        requireOwnerOrAnonymous(story, userId)

        // 기본 정보 — 보낸 필드만 교체.
        request.title?.let { story.title = it }
        request.oneLineIntro?.let { story.oneLineIntro = it }
        request.description?.let { story.description = it }
        request.genres?.let { story.genre = it.joinToString(separator = ", ").ifBlank { null } }

        // 스토리 설정 통글 4필드 — 없으면 생성, 있으면 교체(제작 시 생성되므로 보통 존재).
        request.storySettings?.let { input ->
            val setting = storySettingRepository.findByStoryId(story.id) ?: StorySetting(story = story)
            setting.worldSetting = input.worldSetting
            setting.characterSetting = input.characterSetting
            setting.userRoleSetting = input.userRoleSetting
            setting.ruleSetting = input.ruleSetting
            storySettingRepository.save(setting)
        }

        // 시작 설정 — 없으면 생성, 있으면 교체.
        request.startSetting?.let { input ->
            val startSetting = storyStartSettingRepository.findByStoryId(story.id) ?: StoryStartSetting(story = story)
            startSetting.name = input.name
            startSetting.prologue = input.prologue
            startSetting.startSituation = input.startSituation
            storyStartSettingRepository.save(startSetting)
        }

        val startSetting = storyStartSettingRepository.findByStoryId(story.id)

        // 추천 입력 전체 교체(inputOrder 1-based). 시작 설정이 없으면 저장 대상이 없어 400.
        request.suggestedInputs?.let { inputs ->
            val ss = requireStartSetting(startSetting, "추천 입력")
            val existing = storySuggestedInputRepository.findByStartSettingIdOrderByInputOrderAsc(ss.id)
            storySuggestedInputRepository.deleteAll(existing)
            storySuggestedInputRepository.flush()
            storySuggestedInputRepository.saveAll(
                inputs.mapIndexed { index, text ->
                    StorySuggestedInput(startSetting = ss, inputText = text, inputOrder = (index + 1).toShort())
                },
            )
        }

        // 주요 사건 전체 교체(sort_order 0-based).
        request.mainEvents?.let { events ->
            val existing = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
            storyMainEventRepository.deleteAll(existing)
            storyMainEventRepository.flush()
            storyMainEventRepository.saveAll(
                events.mapIndexed { index, item ->
                    StoryMainEvent(
                        story = story,
                        name = item.name,
                        description = item.description,
                        keySentence = item.keySentence,
                        sortOrder = index.toShort(),
                    )
                },
            )
        }

        // 엔딩 전체 교체(sort_order 1-based). 레거시(enabled=false)까지 지워 유니크 충돌을 피한다.
        request.endings?.let { endings ->
            val ss = requireStartSetting(startSetting, "엔딩")
            storyEndingRepository.deleteByStartSettingId(ss.id)
            storyEndingRepository.flush()
            storyEndingRepository.saveAll(
                endings.mapIndexed { index, item ->
                    StoryEnding(
                        startSetting = ss,
                        name = item.name,
                        minTurns = item.requirement.minTurns,
                        achievementCondition = item.requirement.achievementCondition,
                        epilogue = item.epilogue,
                        sortOrder = (index + 1).toShort(),
                    )
                },
            )
        }

        return buildEditForm(story)
    }

    private fun buildEditForm(story: Story): StoryEditFormResponse {
        val setting = storySettingRepository.findByStoryId(story.id)
        val startSetting = storyStartSettingRepository.findByStoryId(story.id)
        val suggestedInputs = startSetting
            ?.let { storySuggestedInputRepository.findByStartSettingIdOrderByInputOrderAsc(it.id) }
            ?.map { it.inputText }
            ?: emptyList()
        val mainEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
            .map { it.toMainEventResponse() }
        // 활성 엔딩만 노출한다(레거시 enabled=false 제외 — §4-3-8).
        val endings = startSetting
            ?.let { storyEndingRepository.findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(it.id) }
            ?.map { it.toEndingResponse() }
            ?: emptyList()

        return StoryEditFormResponse(
            title = story.title,
            oneLineIntro = story.oneLineIntro,
            description = story.description,
            genres = story.toGenreNames(),
            storySettings = StoryEditSettingsResponse(
                worldSetting = setting?.worldSetting,
                characterSetting = setting?.characterSetting,
                userRoleSetting = setting?.userRoleSetting,
                ruleSetting = setting?.ruleSetting,
            ),
            startSetting = startSetting?.let {
                StoryStartSettingResponse(name = it.name, prologue = it.prologue, startSituation = it.startSituation)
            },
            suggestedInputs = suggestedInputs,
            mainEvents = mainEvents,
            endings = endings,
        )
    }

    /** 소유권 게이트(§4-5): 소유자 없는(게스트) 스토리는 익명 허용, 소유자 있으면 본인만. 위반 시 403. */
    private fun requireOwnerOrAnonymous(story: Story, userId: Long?) {
        if (story.userId != null && story.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        }
    }

    private fun requireStartSetting(startSetting: StoryStartSetting?, field: String): StoryStartSetting =
        startSetting ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "시작 설정이 없어 ${field}을(를) 저장할 수 없습니다.",
        )

    /** 공개 식별자(UUID 문자열)로 스토리를 조회한다. 형식 오류·없음·삭제는 모두 404로 통일한다(IDOR 차단). */
    private fun resolveStory(publicId: String): Story {
        val parsed = try {
            UUID.fromString(publicId)
        } catch (ignored: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }
        return storyRepository.findByPublicIdAndDeletedAtIsNull(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
    }

    private fun Story.toGenreNames(): List<String> =
        genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
