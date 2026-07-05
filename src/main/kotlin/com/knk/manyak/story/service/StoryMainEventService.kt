package com.knk.manyak.story.service

import com.knk.manyak.story.dto.ReplaceMainEventsRequest
import com.knk.manyak.story.dto.StoryMainEventResponse
import com.knk.manyak.story.dto.toMainEventResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 주요 사건의 조회·교체 저장(스펙 §4-3-10, KNK-418). 저작(round-trip) 계약까지이며 런타임 판정 연동은 별도 범위다.
 *
 * 스토리 식별·소유권은 기존 스토리 엔드포인트 관례를 따른다: 공개 식별자(publicId)로 조회하고 없으면 404,
 * 저작 인증(오너 한정)은 일반 제작·수정 도입(KNK-45) 시 스토리 편집 전반과 함께 다룬다.
 */
@Service
class StoryMainEventService(
    private val storyRepository: StoryRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
) {

    @Transactional(readOnly = true)
    fun getMainEvents(storyId: String): List<StoryMainEventResponse> {
        val story = resolveStory(storyId)
        return storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id).map { it.toMainEventResponse() }
    }

    /**
     * 스토리의 주요 사건 전체를 요청 목록으로 교체한다. 표시 순서는 배열 순서(0..n-1)로 부여한다.
     *
     * 기존을 지우고 새로 넣는데, 같은 (story_id, sort_order)로 삭제 전 insert가 겹치면 유니크 위반이 나므로
     * 삭제를 먼저 flush해 DB에 반영한 뒤 insert한다. 개수 상한(10)·필드 검증은 요청 DTO에서 400으로 걸러진다.
     */
    @Transactional
    fun replaceMainEvents(storyId: String, request: ReplaceMainEventsRequest): List<StoryMainEventResponse> {
        val story = resolveStory(storyId)
        storyMainEventRepository.deleteByStoryId(story.id)
        storyMainEventRepository.flush()

        val saved = request.mainEvents.mapIndexed { index, item ->
            StoryMainEvent(
                story = story,
                name = item.name,
                description = item.description,
                keySentence = item.keySentence,
                sortOrder = index.toShort(),
            )
        }
        storyMainEventRepository.saveAll(saved)
        return saved.map { it.toMainEventResponse() }
    }

    /**
     * 공개 식별자(UUID 문자열)로 스토리를 조회한다. 형식이 잘못됐거나 존재하지 않으면(삭제 포함) 404로 통일한다
     * (StoryService.resolveStory와 동일 규칙 — 존재 여부를 노출하지 않아 IDOR 차단).
     */
    private fun resolveStory(publicId: String): Story {
        val parsed = try {
            UUID.fromString(publicId)
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        return storyRepository.findByPublicIdAndDeletedAtIsNull(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
    }
}
