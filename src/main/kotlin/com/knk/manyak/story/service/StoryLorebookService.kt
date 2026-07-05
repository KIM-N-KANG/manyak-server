package com.knk.manyak.story.service

import com.knk.manyak.story.dto.LorebookResponse
import com.knk.manyak.story.dto.ReplaceStoryLorebooksRequest
import com.knk.manyak.story.dto.toLorebookResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryLorebook
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 스토리 참조 로어북의 조회·교체 저장(KNK-421). 로어북은 장르 공용 카탈로그([com.knk.manyak.story.entity.Lorebook])이고,
 * 스토리는 그 id를 story_lorebooks로 참조한다. 이 서비스는 참조 집합의 쓰기 경로다.
 *
 * 스토리는 공개 식별자(publicId)로 조회하고 없으면 404다. 조회(get)는 공개지만, 교체(replace)는 저작 데이터
 * 변조를 막기 위해 **인증 필수 + 스토리 소유자만** 허용한다(비소유자·게스트 스토리는 403).
 */
@Service
class StoryLorebookService(
    private val storyRepository: StoryRepository,
    private val storyLorebookRepository: StoryLorebookRepository,
    private val lorebookRepository: LorebookRepository,
) {

    @Transactional(readOnly = true)
    fun getStoryLorebooks(storyId: String, userId: Long?): List<LorebookResponse> {
        val story = resolveStory(storyId)
        // 공개(PUBLISHED∧PUBLIC) 스토리이거나 소유자만 읽을 수 있다(KNK-401). 비공개 초안 참조 데이터 유출 방지.
        if (!story.isReadableBy(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }
        return storyLorebookRepository.findByStoryIdOrderBySortOrderAscIdAsc(story.id).map { it.toLorebookResponse() }
    }

    /**
     * 스토리의 참조 로어북 전체를 요청 id 목록으로 교체한다. 표시 순서는 배열 순서이며 sort_order는 1부터 부여한다
     * (DB CHECK sort_order > 0). 개수 상한(10)·필드 검증은 요청 DTO에서 400으로 걸러진다.
     *
     * 참조는 카탈로그의 활성 로어북만 가리킬 수 있다. 중복 id(유니크 위반 전 방지)나 존재하지 않거나 비활성인
     * id는 400으로 거른다. 기존을 지운 뒤 새로 넣되, (story_id, lorebook_id) 유니크를 피하려 삭제를 먼저 flush한다.
     */
    @Transactional
    fun replaceStoryLorebooks(
        storyId: String,
        userId: Long,
        request: ReplaceStoryLorebooksRequest,
    ): List<LorebookResponse> {
        // 스토리 행을 락으로 잡아 같은 스토리의 동시 교체를 직렬화한다(삭제 후 동시 insert 시 유니크 위반 방지).
        val story = resolveStory(storyId, forUpdate = true)
        // 저작 데이터 변조 방지: 스토리 소유자만 교체할 수 있다. 게스트 스토리(userId=null)·타인 소유는 403.
        if (story.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        }

        val ids = request.lorebookIds
        // 중복 id는 (story_id, lorebook_id) 유니크 위반을 유발하므로 500 대신 400으로 명확히 거른다.
        if (ids.size != ids.distinct().size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "중복된 로어북 id가 있습니다.")
        }
        // 카탈로그에 존재하는 활성 로어북만 참조할 수 있다. 하나라도 없거나 비활성이면 400.
        val lorebooksById = lorebookRepository.findByIdInAndIsActiveTrue(ids).associateBy { it.id }
        if (lorebooksById.size != ids.size) {
            val invalid = ids.filter { it !in lorebooksById }
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않거나 비활성인 로어북입니다: $invalid")
        }

        storyLorebookRepository.deleteByStoryId(story.id)
        storyLorebookRepository.flush()

        val saved = ids.mapIndexed { index, id ->
            StoryLorebook(
                story = story,
                lorebook = lorebooksById.getValue(id),
                // sort_order는 1-based다(DB CHECK sort_order > 0). 배열 0-based index에 +1해 부여한다.
                sortOrder = (index + 1).toShort(),
            )
        }
        storyLorebookRepository.saveAll(saved)
        return saved.map { it.toLorebookResponse() }
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
