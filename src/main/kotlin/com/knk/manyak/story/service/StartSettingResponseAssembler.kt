package com.knk.manyak.story.service

import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.toEndingResponse
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.springframework.stereotype.Component

/**
 * 스토리의 시작 설정 목록을 응답 객체로 조립한다(KNK-515 복수화). 추천 입력·엔딩은 각 시작 설정 스코프이며,
 * 시작 설정이 여러 개여도 자식 조회가 N+1이 되지 않도록 각각 한 번의 IN 조회로 모아 그룹핑한다.
 * 엔딩은 활성(enabled=true)만 노출한다(레거시 enabled=false 행 제외 — §4-3-10).
 * 공개 상세(GET /stories/{id})와 수정 폼(GET /stories/{id}/edit)이 공유한다.
 */
@Component
class StartSettingResponseAssembler(
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyEndingRepository: StoryEndingRepository,
) {
    fun assemble(storyId: Long): List<StoryStartSettingResponse> {
        val startSettings = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(storyId)
        if (startSettings.isEmpty()) {
            return emptyList()
        }
        val startSettingIds = startSettings.map { it.id }
        val inputsByStartSettingId = storySuggestedInputRepository
            .findByStartSettingIdInOrderByStartSettingIdAscInputOrderAsc(startSettingIds)
            .groupBy({ it.startSetting.id }, { it.inputText })
        val endingsByStartSettingId = storyEndingRepository
            .findByStartSettingIdInAndEnabledTrueOrderByStartSettingIdAscSortOrderAsc(startSettingIds)
            .groupBy { it.startSetting.id }
        return startSettings.map { startSetting ->
            StoryStartSettingResponse(
                id = startSetting.publicId.toString(),
                name = startSetting.name,
                prologue = startSetting.prologue,
                startSituation = startSetting.startSituation,
                suggestedInputs = inputsByStartSettingId[startSetting.id].orEmpty(),
                endings = endingsByStartSettingId[startSetting.id].orEmpty().map { it.toEndingResponse() },
            )
        }
    }
}
