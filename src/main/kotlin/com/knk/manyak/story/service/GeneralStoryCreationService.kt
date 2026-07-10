package com.knk.manyak.story.service

import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.GeneralStartSettingInput
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.toEndingResponse
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
    private val storyThumbnailLinker: StoryThumbnailLinker,
    private val storyBackgroundImageLinker: StoryBackgroundImageLinker,
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
                // 표지는 등록 시 1회 확정한다(§4-3-9). 후보가 없으면 null이고 프론트엔드가 placeholder를 그린다.
                thumbnailImageKey = storyThumbnailLinker.linkFor(request.genres),
                // 등록 = 발행(초안 개념 없음). 공개 범위는 요청 선택값(기본 PRIVATE).
                visibility = request.visibility,
            ),
        )
        // 채팅 배경 후보도 등록 시 1회 확정한다(§4-3-9). 일반 제작은 컴파일이 없어 인물 매핑은 생기지 않는다.
        storyBackgroundImageLinker.linkFor(story.id, request.genres)
        storySettingRepository.save(
            StorySetting(
                story = story,
                worldSetting = request.storySettings.worldSetting,
                characterSetting = request.storySettings.characterSetting,
                userRoleSetting = request.storySettings.userRoleSetting,
                ruleSetting = request.storySettings.ruleSetting,
            ),
        )
        // 주요 사건은 스토리 스코프(sort_order 0-based). 이름은 스토리 내에서 유니크해야 한다
        // (이름 기반 런타임 식별, 완결·목표 매칭 모호성 방지 — KNK-523). 없으면 저장 생략.
        requireDistinctMainEventNames(request.mainEvents.map { it.name })
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

        // 시작 설정별로 저장한다(KNK-515 복수화). 추천 입력·엔딩은 각 시작 설정 스코프다.
        val startSettingResponses = request.startSettings.map { input -> persistStartSetting(story, input) }

        return SimpleStoryCreateResponse(
            id = story.publicId.toString(),
            title = story.title,
            oneLineIntro = story.oneLineIntro,
            description = story.description,
            genres = request.genres,
            startSettings = startSettingResponses,
        )
    }

    /** 시작 설정 하나와 그 스코프의 추천 입력·엔딩을 저장하고 응답 객체로 만든다(KNK-515 복수화). */
    private fun persistStartSetting(story: Story, input: GeneralStartSettingInput): StoryStartSettingResponse {
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = input.name,
                prologue = input.prologue,
                startSituation = input.startSituation,
            ),
        )
        // 추천 입력은 시작 설정별 목록(1-based order). 채팅 시작 화면 계약과 동일하게 정확히 3개다.
        val suggestedInputs = storySuggestedInputRepository.saveAll(
            input.suggestedInputs.mapIndexed { index, text ->
                StorySuggestedInput(
                    startSetting = startSetting,
                    inputText = text,
                    inputOrder = (index + 1).toShort(),
                )
            },
        ).map { it.inputText }
        // 엔딩 이름은 시작 설정 내에서 유니크해야 한다(이름 기반 식별, 표시 모호성 방지 — KNK-523).
        requireDistinctEndingNames(input.endings.map { it.name })
        // 엔딩은 시작 설정 스코프(sort_order 1-based, ck_story_endings_order > 0). 없으면 저장 생략.
        val endings = if (input.endings.isEmpty()) {
            emptyList()
        } else {
            storyEndingRepository.saveAll(
                input.endings.mapIndexed { index, item ->
                    StoryEnding(
                        startSetting = startSetting,
                        name = item.name,
                        minTurns = item.requirement.minTurns,
                        achievementCondition = item.requirement.achievementCondition,
                        epilogue = item.epilogue,
                        sortOrder = (index + 1).toShort(),
                    )
                },
            ).map { it.toEndingResponse() }
        }
        return StoryStartSettingResponse(
            id = startSetting.publicId.toString(),
            name = startSetting.name,
            prologue = startSetting.prologue,
            startSituation = startSetting.startSituation,
            suggestedInputs = suggestedInputs,
            endings = endings,
        )
    }
}
