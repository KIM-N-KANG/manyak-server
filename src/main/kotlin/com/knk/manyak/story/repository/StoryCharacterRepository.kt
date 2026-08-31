package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCharacter
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCharacterRepository : JpaRepository<StoryCharacter, Long> {

    // 스토리의 인물을 저장 순서(=컴파일 응답 순서)로 조회한다.
    fun findByStoryIdOrderByIdAsc(storyId: Long): List<StoryCharacter>

    // 채팅 요청에 실을 인물-이미지 매핑(KNK-943). 이미지 생성·업로드에 실패해 image_url이 NULL인 인물은
    // AI가 URL 없는 태그를 만들 수 없어야 하므로 조회 단계에서 제외한다.
    fun findByStoryIdAndImageUrlIsNotNullOrderByIdAsc(storyId: Long): List<StoryCharacter>
}
