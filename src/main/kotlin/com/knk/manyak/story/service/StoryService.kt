package com.knk.manyak.story.service

import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.global.security.isOwnerAccessAllowed
import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.LorebookListItemResponse
import com.knk.manyak.story.dto.LorebookResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.dto.toMainEventResponse
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryLorebook
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.UserStoryEndingReachRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class StoryService(
    private val storyRepository: StoryRepository,
    private val startSettingResponseAssembler: StartSettingResponseAssembler,
    private val lorebookRepository: LorebookRepository,
    private val storyLorebookRepository: StoryLorebookRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val userStoryEndingReachRepository: UserStoryEndingReachRepository,
    private val storyChatRepository: StoryChatRepository,
) {

    @Transactional(readOnly = true)
    fun getLorebooks(genre: String?): List<LorebookListItemResponse> {
        val lorebooks = genre
            ?.let { lorebookRepository.findByGenreAndIsActiveTrueOrderBySortOrderAscIdAsc(it) }
            ?: lorebookRepository.findByIsActiveTrueOrderByGenreAscSortOrderAscIdAsc()
        return lorebooks.map { it.toListItemResponse() }
    }

    @Transactional(readOnly = true)
    fun getStoriesByIds(request: BatchStoryRequest, userId: Long?): List<StorySummaryResponse> {
        // 공개 식별자(UUID 문자열)로 받는다. 형식이 잘못된 값은 매칭될 수 없으므로 조용히 제외한다.
        val requestedPublicIds = request.storyIds.mapNotNull { parsePublicIdOrNull(it) }.distinct()
        // 유효한 식별자가 하나도 없으면 DB 조회 없이 즉시 빈 목록을 반환한다.
        if (requestedPublicIds.isEmpty()) {
            return emptyList()
        }
        // 공개 목록은 공개(PUBLISHED∧PUBLIC) 스토리만, 단 요청자가 소유자면 자신의 비공개·초안도 노출한다(KNK-401).
        val storiesByPublicId = storyRepository.findAllByPublicIdInAndDeletedAtIsNull(requestedPublicIds)
            .filter { it.isReadableBy(userId) }
            .associateBy { it.publicId }
        // 요청 순서를 보존한다. 존재하지 않거나 삭제된 스토리는 자연히 제외된다.
        return requestedPublicIds
            .mapNotNull { storiesByPublicId[it] }
            .toSummaryResponses()
    }

    /**
     * 회원 서재(KNK-447): 요청자가 소유한 스토리 카드를 생성 최신순으로 반환한다. 소프트 삭제는 제외한다.
     * 카드 스키마는 [getStoriesByIds](/stories/batch)와 동일하다([Story.toSummaryResponse]).
     */
    @Transactional(readOnly = true)
    fun getMyStories(userId: Long, limit: Int): List<StorySummaryResponse> =
        storyRepository
            .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, limit))
            .toSummaryResponses()

    @Transactional(readOnly = true)
    fun getStoryDetail(storyId: String, userId: Long?): StoryDetailResponse {
        val story = resolveStory(storyId)
        // 공개 상세 조회는 공개(PUBLISHED∧PUBLIC) 스토리만, 단 소유자는 자신의 비공개·초안도 볼 수 있다(KNK-401).
        // 존재 노출 최소화를 위해 접근 불가면 존재하지 않는 것과 동일하게 404로 통일한다.
        if (!story.isReadableBy(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }

        // 내부 PK(story.id)로 자식 데이터를 조회한다. 외부 식별자(public_id)는 응답에만 노출한다.
        // 시작 설정 복수화(KNK-515): 등록 순서로 전부 싣고, 추천 입력·엔딩은 각 시작 설정에 종속시킨다.
        val startSettings = startSettingResponseAssembler.assemble(story.id)

        val lorebooks = storyLorebookRepository.findByStoryIdOrderBySortOrderAscIdAsc(story.id)
            .map { it.toLorebookResponse() }
        val mainEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
            .map { it.toMainEventResponse() }
        // 요청 회원이 이 스토리에서 도달한 엔딩 이름 집계(스펙 §4-3-10). 게스트(userId null)는 빈 배열.
        // 저장은 ending id 기준이라 무모호하며, 노출은 이름으로 한다(엔딩 목록과 이름으로 상관, KNK-462).
        val reachedEndings = resolveReachedEndingNames(userId, story.id)

        return StoryDetailResponse(
            id = story.publicId.toString(),
            // 썸네일 소스 배선은 별도 범위(KNK-515은 필드명만 확정). 현재는 null.
            thumbnailUrl = null,
            title = story.title,
            oneLineIntro = story.oneLineIntro.orEmpty(),
            description = story.description,
            genres = story.toGenreNames(),
            hashtags = emptyList(),
            author = null,
            turnCount = storyChatRepository.sumCurrentTurnByStoryId(story.id),
            likeCount = 0,
            startSettings = startSettings,
            visibility = story.visibility,
            status = story.status,
            lorebooks = lorebooks,
            mainEvents = mainEvents,
            reachedEndings = reachedEndings,
            createdAt = story.createdAt,
        )
    }

    /** 회원이 한 스토리에서 도달한 엔딩 이름을 표시 순서(sort_order)로 반환한다. 게스트는 빈 목록. */
    private fun resolveReachedEndingNames(userId: Long?, storyId: Long): List<String> {
        if (userId == null) {
            return emptyList()
        }
        val reachedIds = userStoryEndingReachRepository.findByUserIdAndStoryId(userId, storyId).map { it.endingId }
        if (reachedIds.isEmpty()) {
            return emptyList()
        }
        return storyEndingRepository.findAllById(reachedIds).sortedBy { it.sortOrder }.map { it.name }
    }

    /**
     * 스토리를 소프트 삭제한다. 행을 물리 삭제하지 않고 deletedAt만 기록해 자식 데이터(설정·시작 설정·추천 입력)를 보존한다.
     * 형식이 잘못됐거나 이미 삭제됐거나 존재하지 않으면 404로 통일한다.
     * 존재 여부를 노출하지 않도록 소유권 403은 404(없음·이미 삭제) 판정 뒤에 적용한다.
     */
    @Transactional
    fun deleteStory(storyId: String, userId: Long?) {
        // 소유권 검사와 deletedAt 기록 사이에 마이그레이션 클레임이 끼어드는 경쟁을 막으려 행에 비관적 쓰기 락을 건다(KNK-69).
        val story = resolveStoryForUpdate(storyId)
        // 소유권 게이트(§4-5, KNK-480): 게스트 스토리는 게스트만, 소유 스토리는 소유자만. 회원의 NULL 소유 스토리 삭제도 차단. 위반 시 403.
        if (!isOwnerAccessAllowed(story.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "스토리를 삭제할 권한이 없습니다.")
        }
        // @Transactional 트랜잭션 커밋 시 더티 체킹으로 deletedAt 변경이 반영된다. 명시적 save 불필요.
        story.deletedAt = Instant.now()
    }

    /**
     * 공개 식별자(UUID 문자열)로 스토리를 조회한다. 형식이 잘못됐거나 존재하지 않으면(삭제 포함) 404로 통일한다.
     * 순차 정수든 임의 문자열이든 동일하게 404를 반환해 존재 여부를 노출하지 않는다(IDOR 차단).
     */
    private fun resolveStory(publicId: String): Story {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        return storyRepository.findByPublicIdAndDeletedAtIsNull(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
    }

    /** [resolveStory]와 같으나 행에 비관적 쓰기 락을 걸어 조회한다(삭제 소유권 검사의 마이그레이션 클레임 경쟁 차단 — KNK-69). */
    private fun resolveStoryForUpdate(publicId: String): Story {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        return storyRepository.findByPublicIdAndDeletedAtIsNullForUpdate(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
    }

    private fun parsePublicIdOrNull(raw: String): UUID? =
        try {
            UUID.fromString(raw)
        } catch (ignored: IllegalArgumentException) {
            null
        }

    private fun Lorebook.toListItemResponse(): LorebookListItemResponse =
        LorebookListItemResponse(id = id, name = name, genre = genre)

    private fun StoryLorebook.toLorebookResponse(): LorebookResponse =
        LorebookResponse(
            id = lorebook.id,
            name = lorebook.name,
            genre = lorebook.genre,
            content = lorebook.content,
        )

    private fun Story.toGenreNames(): List<String> =
        genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 스토리 목록을 카드 응답으로 매핑한다. turnCount는 한 번의 배치 집계로 채운다(N+1 방지). */
    private fun List<Story>.toSummaryResponses(): List<StorySummaryResponse> {
        if (isEmpty()) {
            return emptyList()
        }
        val turnCountByStoryId = storyChatRepository.sumCurrentTurnByStoryIds(map { it.id })
            .associate { it.storyId to it.turnCount }
        return map { it.toSummaryResponse(turnCount = turnCountByStoryId[it.id] ?: 0) }
    }

    private fun Story.toSummaryResponse(turnCount: Long): StorySummaryResponse =
        StorySummaryResponse(
            id = publicId.toString(),
            title = title,
            oneLineIntro = oneLineIntro.orEmpty(),
            genres = toGenreNames(),
            author = null,
            turnCount = turnCount,
            likeCount = 0,
            status = this.status,
            createdAt = createdAt,
        )
}
