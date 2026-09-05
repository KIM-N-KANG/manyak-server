package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCharacter
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StoryCharacterRepository : JpaRepository<StoryCharacter, Long> {

    // 스토리의 인물을 저장 순서(=컴파일 응답 순서)로 조회한다.
    fun findByStoryIdOrderByIdAsc(storyId: Long): List<StoryCharacter>

    // 채팅 요청에 실을 인물-이미지 매핑(KNK-943). 이미지 생성·업로드에 실패해 image_url이 NULL인 인물은
    // AI가 URL 없는 태그를 만들 수 없어야 하므로 조회 단계에서 제외한다.
    fun findByStoryIdAndImageUrlIsNotNullOrderByIdAsc(storyId: Long): List<StoryCharacter>

    /**
     * 인물 행을 비관적 쓰기 락으로 조회한다(KNK-1126). 이미지 상한(인물당 10장) 판정이 "개수를 읽고 → 넣는다"라
     * 잠금이 없으면 상한 직전의 동시 추가 둘이 모두 통과해 11장이 되고 `sort_order`도 겹친다.
     *
     * 잠그는 대상이 스토리가 아니라 **인물**인 이유: 상한이 인물 단위라 그 행 하나면 충분하고, 스토리를 잠그면
     * 같은 스토리의 다른 인물에 올리는 요청까지 줄을 서게 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM StoryCharacter c WHERE c.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): StoryCharacter?
}
