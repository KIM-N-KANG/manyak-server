package com.knk.manyak.story.service

import com.knk.manyak.story.dto.StorySearchMaterial
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryLikeRepository
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/**
 * 검색 모듈이 문서를 조립할 스토리 값을 묶음으로 읽는다(STORY 4-1-27, KNK-2080). 스토리 목록마다 스토리, 인물 이름, 좋아요 수를
 * 한 번씩 읽는다. 작성자와 누적 턴 수는 검색 모듈이 계정 조회와 [StoryTurnCounts]로 읽는다.
 *
 * 트랜잭션을 따로 열지 않는다. 호출자(검색 문서 읽기)의 읽기 전용 트랜잭션에서 같은 스냅샷으로 읽는다.
 */
@Component
class StorySearchSource(
    private val stories: StoryRepository,
    private val characters: StoryCharacterRepository,
    private val likes: StoryLikeRepository,
) {
    /** 없는 ID는 빠지고 순서는 스토리 ID 오름차순이다. 삭제된 스토리도 담는다. 받은 ID를 IN 하나로 읽는다. */
    fun materials(storyIds: Collection<Long>): List<StorySearchMaterial> = materialsOf(stories.findAllById(storyIds).sortedBy { it.id })

    /** 스토리 ID 순 [page]번째 [size]건. */
    fun materialsPage(page: Int, size: Int): List<StorySearchMaterial> =
        materialsOf(stories.findAll(PageRequest.of(page, size, Sort.by("id"))).content)

    private fun materialsOf(rows: List<Story>): List<StorySearchMaterial> {
        if (rows.isEmpty()) return emptyList()
        val storyIds = rows.map { it.id }
        val characterNames = characters.findByStoryIdInOrderByStoryIdAscIdAsc(storyIds).groupBy({ it.story.id }, { it.name })
        val likeCountByStoryId = likes.countByStoryIds(storyIds).associate { it.storyId to it.likeCount }
        return rows.map { story ->
            StorySearchMaterial(
                id = story.id, publicId = story.publicId, userId = story.userId, title = story.title,
                oneLineIntro = story.oneLineIntro, protagonistName = story.protagonistName, genre = story.genre,
                thumbnailImageUrl = story.thumbnailImageUrl, thumbnailImageKey = story.thumbnailImageKey,
                thumbnailModerationStatus = story.thumbnailModerationStatus, createdAt = story.createdAt,
                publiclyListed = story.isPubliclyListed(),
                characterNames = characterNames[story.id].orEmpty(), likeCount = likeCountByStoryId[story.id] ?: 0,
            )
        }
    }
}
