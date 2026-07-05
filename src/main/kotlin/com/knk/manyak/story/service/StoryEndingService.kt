package com.knk.manyak.story.service

import com.knk.manyak.story.dto.ReplaceEndingsRequest
import com.knk.manyak.story.dto.StoryEndingResponse
import com.knk.manyak.story.dto.toEndingResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 엔딩의 조회·교체 저장(KNK-419). 엔딩은 시작 설정(start_setting) 하위다.
 *
 * 스토리는 공개 식별자(publicId)로 조회하고 없으면 404다. 조회(get)는 공개지만, 교체(replace)는 저작 데이터
 * 변조를 막기 위해 **인증 필수 + 스토리 소유자만** 허용한다(비소유자·게스트 스토리는 403).
 * 엔딩을 매달 시작 설정이 없으면 409다(간편 제작 컴파일 또는 시작 설정 저작이 선행돼야 한다).
 */
@Service
class StoryEndingService(
    private val storyRepository: StoryRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storyEndingRepository: StoryEndingRepository,
) {

    @Transactional(readOnly = true)
    fun getEndings(storyId: String, userId: Long?): List<StoryEndingResponse> {
        val story = resolveStory(storyId)
        // 공개(PUBLISHED∧PUBLIC) 스토리이거나 소유자만 읽을 수 있다(KNK-401). 비공개 초안 저작 데이터 유출 방지.
        if (!story.isReadableBy(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }
        // 시작 설정이 없으면 매달린 엔딩도 있을 수 없으므로 빈 배열이다(조회는 관대하게).
        val startSetting = storyStartSettingRepository.findByStoryId(story.id)
            ?: return emptyList()
        return storyEndingRepository.findByStartSettingIdOrderBySortOrderAsc(startSetting.id)
            .map { it.toEndingResponse() }
    }

    /**
     * 시작 설정의 엔딩 전체를 요청 목록으로 교체한다. 표시 순서는 배열 순서이며 sort_order는 1부터 부여한다
     * (DB CHECK sort_order > 0). 개수 상한(10)·필드 검증은 요청 DTO에서 400으로 걸러진다.
     *
     * 기존을 지우고 새로 넣는데, 같은 (start_setting_id, sort_order)로 삭제 전 insert가 겹치면 유니크 위반이
     * 나므로 삭제를 먼저 flush해 DB에 반영한 뒤 insert한다.
     */
    @Transactional
    fun replaceEndings(
        storyId: String,
        userId: Long,
        request: ReplaceEndingsRequest,
    ): List<StoryEndingResponse> {
        // 스토리 행을 락으로 잡아 같은 스토리(1:1 시작 설정)의 동시 교체를 직렬화한다. 없으면 둘 다 delete 후
        // 같은 sort_order로 동시 insert하다 (start_setting_id, sort_order) 유니크 위반으로 한쪽이 500이 된다.
        val story = resolveStory(storyId, forUpdate = true)
        // 저작 데이터 변조 방지: 스토리 소유자만 교체할 수 있다. 소유자가 없는 게스트 스토리(userId=null)나
        // 타인 소유는 403이다. 공개 UUID만 알면 누구나 저작 데이터를 덮어쓰던 노출을 막는다.
        if (story.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 수정할 권한이 없습니다.")
        }
        // 엔딩은 시작 설정 하위다. 매달 부모가 없으면(간편 제작·시작 설정 저작 미선행) 404 스토리와 구분해 409로 알린다.
        val startSetting = storyStartSettingRepository.findByStoryId(story.id)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "시작 설정이 없어 엔딩을 저장할 수 없습니다.")

        storyEndingRepository.deleteByStartSettingId(startSetting.id)
        storyEndingRepository.flush()

        val saved = request.endings.mapIndexed { index, item ->
            StoryEnding(
                startSetting = startSetting,
                title = item.title,
                content = item.content,
                conditionText = item.conditionText,
                // sort_order는 1-based다(DB CHECK sort_order > 0). 배열 0-based index에 +1해 부여한다.
                sortOrder = (index + 1).toShort(),
                enabled = item.enabled,
            )
        }
        storyEndingRepository.saveAll(saved)
        return saved.map { it.toEndingResponse() }
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
