package com.knk.manyak.story.service

import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 일반 제작 스토리 등록(단발, 스펙 §4-3-8). 사용자가 폼에 직접 입력한 스토리 구성 항목을 검증 후 그대로 저장한다.
 *
 * 간편 제작과 달리 AI(컴파일)를 호출하지 않으므로 크레딧 소모·게스트 체험 한도 카운트가 없다. 저장하는 애그리거트
 * (스토리·설정·시작 설정·추천 입력·주요 사건·엔딩) 구조는 간편 제작 산출물과 같아, 이후 상세 조회·채팅 시작이
 * 제작 방식과 무관하게 동일하게 동작한다.
 */
@Service
class GeneralStoryCreationService(
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val suspensionGuard: SuspensionGuard,
) {

    /**
     * 인증 선택(간편 제작과 동일 — 유효 토큰이면 [userId] 귀속, 익명이면 null). 응답은 간편 제작과 동일한
     * `{id, title, oneLineIntro, description, genres, startSetting}`이다.
     */
    @Transactional
    fun createGeneralStory(request: CreateGeneralStoryRequest, userId: Long?): SimpleStoryCreateResponse {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        // 장르는 현행 방식대로 stories.genre에 쉼표 결합 저장한다(§4-3-8).
        val genre = request.genres.joinToString(separator = ", ").ifBlank { null }

        val story = storyRepository.save(
            Story(
                userId = userId,
                title = request.title,
                oneLineIntro = request.oneLineIntro,
                description = request.description,
                genre = genre,
                // 등록 = 발행(초안 개념 없음). 공개 범위는 요청 선택값(기본 PRIVATE).
                visibility = request.visibility,
            ),
        )
        storySettingRepository.save(
            StorySetting(
                story = story,
                worldSetting = request.storySettings.worldSetting,
                characterSetting = request.storySettings.characterSetting,
                userRoleSetting = request.storySettings.userRoleSetting,
                ruleSetting = request.storySettings.ruleSetting,
            ),
        )
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = request.startSetting.name,
                prologue = request.startSetting.prologue,
                startSituation = request.startSetting.startSituation,
            ),
        )
        // 추천 입력은 시작 설정별 목록(1-based order). 채팅 시작 화면 계약과 동일하게 정확히 3개다.
        storySuggestedInputRepository.saveAll(
            request.suggestedInputs.mapIndexed { index, text ->
                StorySuggestedInput(
                    startSetting = startSetting,
                    inputText = text,
                    inputOrder = (index + 1).toShort(),
                )
            },
        )
        // 주요 사건은 스토리 소유(sort_order 0-based). 없으면 저장 생략.
        if (request.mainEvents.isNotEmpty()) {
            storyMainEventRepository.saveAll(
                request.mainEvents.mapIndexed { index, item ->
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
        // 엔딩은 시작 설정 스코프(sort_order 1-based, ck_story_endings_order > 0). 없으면 저장 생략.
        if (request.endings.isNotEmpty()) {
            storyEndingRepository.saveAll(
                request.endings.mapIndexed { index, item ->
                    StoryEnding(
                        startSetting = startSetting,
                        name = item.name,
                        minTurns = item.requirement.minTurns,
                        achievementCondition = item.requirement.achievementCondition,
                        epilogue = item.epilogue,
                        sortOrder = (index + 1).toShort(),
                    )
                },
            )
        }

        return SimpleStoryCreateResponse(
            id = story.publicId.toString(),
            title = story.title,
            oneLineIntro = story.oneLineIntro,
            description = story.description,
            genres = request.genres,
            startSetting = StoryStartSettingResponse(
                name = startSetting.name,
                prologue = startSetting.prologue,
                startSituation = startSetting.startSituation,
            ),
        )
    }
}
