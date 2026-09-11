package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCharacterImage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface StoryCharacterImageRepository : JpaRepository<StoryCharacterImage, Long> {

    /** 인물의 이미지를 표시 순서로. 상세·편집 폼·상한 판정이 모두 이 순서를 쓴다. */
    fun findByCharacterIdOrderBySortOrderAscIdAsc(characterId: Long): List<StoryCharacterImage>

    fun findByCharacterIdAndPublicId(characterId: Long, publicId: UUID): StoryCharacterImage?

    fun countByCharacterId(characterId: Long): Long

    fun existsByCharacterIdAndImageName(characterId: Long, imageName: String): Boolean

    /**
     * 스토리의 인물 이미지 전부(채팅 요청 `character_images[]`·상세 대표 이미지). 인물을 조인해 한 번에 읽어
     * 인물 수만큼 쿼리가 늘지 않게 한다. 순서는 인물 등록순 → 이미지 표시순이다.
     */
    @Query(
        """
        SELECT i FROM StoryCharacterImage i
        JOIN FETCH i.character c
        WHERE c.story.id = :storyId
        ORDER BY c.id ASC, i.sortOrder ASC, i.id ASC
        """,
    )
    fun findAllByStoryId(@Param("storyId") storyId: Long): List<StoryCharacterImage>
}
