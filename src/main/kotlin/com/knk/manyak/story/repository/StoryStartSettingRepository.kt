package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryStartSetting
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StoryStartSettingRepository : JpaRepository<StoryStartSetting, Long> {
    // 스토리의 시작 설정 전체(등록 순서, KNK-515 복수화). 상세 응답·정합 검사에 쓴다.
    fun findAllByStoryIdOrderByIdAsc(storyId: Long): List<StoryStartSetting>

    // 스토리의 기본(첫) 시작 설정. POST /chats에서 startSettingId 미지정 시 폴백.
    fun findFirstByStoryIdOrderByIdAsc(storyId: Long): StoryStartSetting?

    // 외부 식별자(UUID)로 시작 설정을 조회한다. POST /chats의 startSettingId 해소용(소속 스토리 검증은 호출부).
    fun findByPublicId(publicId: UUID): StoryStartSetting?
}
