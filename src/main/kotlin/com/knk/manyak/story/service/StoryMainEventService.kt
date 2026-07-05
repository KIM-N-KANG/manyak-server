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
 * 스토리는 공개 식별자(publicId)로 조회하고 없으면 404다. 조회(get)는 공개지만, 교체(replace)는 저작 데이터
 * 변조를 막기 위해 **인증 필수 + 스토리 소유자만** 허용한다(비소유자·게스트 스토리는 403).
 */
@Service
class StoryMainEventService(
    private val storyRepository: StoryRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
) {

    @Transactional(readOnly = true)
    fun getMainEvents(storyId: String, userId: Long?): List<StoryMainEventResponse> {
        val story = resolveStory(storyId)
        // 공개(PUBLISHED∧PUBLIC) 스토리이거나 소유자만 읽을 수 있다(KNK-401). 비공개 초안 저작 데이터 유출 방지.
        if (!story.isReadableBy(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }
        return storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id).map { it.toMainEventResponse() }
    }

    /**
     * 스토리의 주요 사건 전체를 요청 목록으로 교체한다. 표시 순서는 배열 순서(0..n-1)로 부여한다.
     *
     * 기존을 지우고 새로 넣는데, 같은 (story_id, sort_order)로 삭제 전 insert가 겹치면 유니크 위반이 나므로
     * 삭제를 먼저 flush해 DB에 반영한 뒤 insert한다. 개수 상한(10)·필드 검증은 요청 DTO에서 400으로 걸러진다.
     */
    @Transactional
    fun replaceMainEvents(
        storyId: String,
        userId: Long,
        request: ReplaceMainEventsRequest,
    ): List<StoryMainEventResponse> {
        // 스토리 행을 락으로 잡아 같은 스토리의 동시 교체를 직렬화한다. 없으면 둘 다 delete 후 sort_order 0부터
        // 동시 insert하다 (story_id, sort_order) 유니크 위반으로 한쪽이 500이 된다. 락이면 뒤 요청이 앞을 기다려
        // 결정적으로 교체된다(마지막 요청이 최종 상태).
        val story = resolveStory(storyId, forUpdate = true)
        // 저작 데이터 변조 방지(KNK-418 P1): 스토리 소유자만 교체할 수 있다. 소유자가 없는 게스트 스토리(userId=null)나
        // 타인 소유는 403이다. 공개 UUID만 알면 누구나 저작 데이터를 덮어쓰던 노출을 막는다.
        if (story.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        }
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
    private fun resolveStory(publicId: String, forUpdate: Boolean = false): Story {
        val parsed = try {
            UUID.fromString(publicId)
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        val story = if (forUpdate) {
            storyRepository.findByPublicIdAndDeletedAtIsNullForUpdate(parsed)
        } else {
            storyRepository.findByPublicIdAndDeletedAtIsNull(parsed)
        }
        return story ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
    }
}
