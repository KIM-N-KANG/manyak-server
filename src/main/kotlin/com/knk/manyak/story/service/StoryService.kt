package com.knk.manyak.story.service

import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.CreateStoryDraftRequest
import com.knk.manyak.story.dto.LorebookListItemResponse
import com.knk.manyak.story.dto.LorebookResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryDraftResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.dto.toEndingResponse
import com.knk.manyak.story.dto.toMainEventResponse
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryLorebook
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
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
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val lorebookRepository: LorebookRepository,
    private val storyLorebookRepository: StoryLorebookRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
) {

    @Transactional(readOnly = true)
    fun getLorebooks(genre: String?): List<LorebookListItemResponse> {
        val lorebooks = genre
            ?.let { lorebookRepository.findByGenreAndIsActiveTrueOrderBySortOrderAscIdAsc(it) }
            ?: lorebookRepository.findByIsActiveTrueOrderByGenreAscSortOrderAscIdAsc()
        return lorebooks.map { it.toListItemResponse() }
    }

    @Transactional(readOnly = true)
    fun getStoriesByIds(request: BatchStoryRequest): List<StorySummaryResponse> {
        // 공개 식별자(UUID 문자열)로 받는다. 형식이 잘못된 값은 매칭될 수 없으므로 조용히 제외한다.
        val requestedPublicIds = request.storyIds.mapNotNull { parsePublicIdOrNull(it) }.distinct()
        // 유효한 식별자가 하나도 없으면 DB 조회 없이 즉시 빈 목록을 반환한다.
        if (requestedPublicIds.isEmpty()) {
            return emptyList()
        }
        // 공개 목록도 PUBLISHED이면서 PUBLIC인 스토리만 노출한다(KNK-401). 초안·비공개는 제외된다.
        val storiesByPublicId = storyRepository.findAllByPublicIdInAndDeletedAtIsNull(requestedPublicIds)
            .filter { it.isPubliclyVisible() }
            .associateBy { it.publicId }
        // 요청 순서를 보존한다. 존재하지 않거나 삭제된 스토리는 자연히 제외된다.
        return requestedPublicIds
            .mapNotNull { storiesByPublicId[it] }
            .map { it.toSummaryResponse() }
    }

    /**
     * 회원 서재(KNK-447): 요청자가 소유한 스토리 카드를 생성 최신순으로 반환한다. 소프트 삭제는 제외한다.
     * 카드 스키마는 [getStoriesByIds](/stories/batch)와 동일하다([Story.toSummaryResponse]).
     */
    @Transactional(readOnly = true)
    fun getMyStories(userId: Long, limit: Int): List<StorySummaryResponse> =
        storyRepository
            .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, limit))
            .map { it.toSummaryResponse() }

    @Transactional(readOnly = true)
    fun getStoryDetail(storyId: String): StoryDetailResponse {
        val story = resolveStory(storyId)
        // 공개 상세 조회는 PUBLISHED이면서 PUBLIC인 스토리만 노출한다(KNK-401). 초안·비공개는 소유자만 저작 API로 접근한다.
        if (!story.isPubliclyVisible()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }

        // 내부 PK(story.id)로 자식 데이터를 조회한다. 외부 식별자(public_id)는 응답에만 노출한다.
        val startSetting = storyStartSettingRepository.findByStoryId(story.id)
        val suggestedInputs = startSetting
            ?.let { storySuggestedInputRepository.findByStartSettingIdOrderByInputOrderAsc(it.id) }
            ?.map { it.inputText }
            ?: emptyList()

        val lorebooks = storyLorebookRepository.findByStoryIdOrderBySortOrderAscIdAsc(story.id)
            .map { it.toLorebookResponse() }
        // 엔딩은 시작 설정(start_setting) 하위다(KNK-419). 시작 설정이 없으면 매달린 엔딩도 없으므로 빈 배열.
        val endings = startSetting
            ?.let { storyEndingRepository.findByStartSettingIdOrderBySortOrderAsc(it.id) }
            ?.map { it.toEndingResponse() }
            ?: emptyList()
        val mainEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
            .map { it.toMainEventResponse() }

        return StoryDetailResponse(
            id = story.publicId.toString(),
            coverImageUrl = null,
            title = story.title,
            oneLineIntro = story.oneLineIntro.orEmpty(),
            description = story.description,
            genres = story.toGenreNames(),
            hashtags = emptyList(),
            author = null,
            chatCount = 0,
            likeCount = 0,
            startSetting = startSetting?.let {
                StoryStartSettingResponse(
                    name = it.name,
                    prologue = it.prologue,
                    startSituation = it.startSituation,
                )
            },
            suggestedInputs = suggestedInputs,
            visibility = story.visibility,
            status = story.status,
            lorebooks = lorebooks,
            endings = endings,
            mainEvents = mainEvents,
            createdAt = story.createdAt,
        )
    }

    /**
     * 스토리를 소프트 삭제한다. 행을 물리 삭제하지 않고 deletedAt만 기록해 자식 데이터(설정·시작 설정·추천 입력)를 보존한다.
     * 형식이 잘못됐거나 이미 삭제됐거나 존재하지 않으면 404로 통일한다.
     */
    @Transactional
    fun deleteStory(storyId: String) {
        val story = resolveStory(storyId)
        // @Transactional 트랜잭션 커밋 시 더티 체킹으로 deletedAt 변경이 반영된다. 명시적 save 불필요.
        story.deletedAt = Instant.now()
    }

    /**
     * 일반 모드 초안(draft) 스토리를 생성한다(KNK-401). 인증 사용자 소유로 status=DRAFT, visibility=PRIVATE로 만든다.
     * 기본정보는 선택이며, 이후 세계관 등 각 탭이 부분 저장(자동저장)으로 채운다.
     */
    @Transactional
    fun createDraft(userId: Long, request: CreateStoryDraftRequest): StoryDraftResponse {
        val story = storyRepository.save(
            Story(
                userId = userId,
                title = request.title ?: "",
                oneLineIntro = request.oneLineIntro,
                description = request.description,
                genre = request.genre,
                status = StoryStatus.DRAFT,
                visibility = StoryVisibility.PRIVATE,
            ),
        )
        return StoryDraftResponse(id = story.publicId.toString(), status = story.status)
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

    // 공개 조회 노출 조건: 발행(PUBLISHED)이면서 공개(PUBLIC). 초안·비공개는 제외한다(KNK-401).
    private fun Story.isPubliclyVisible(): Boolean =
        status == StoryStatus.PUBLISHED && visibility == StoryVisibility.PUBLIC

    private fun Story.toSummaryResponse(): StorySummaryResponse =
        StorySummaryResponse(
            id = publicId.toString(),
            title = title,
            oneLineIntro = oneLineIntro.orEmpty(),
            genres = toGenreNames(),
            author = null,
            chatCount = 0,
            likeCount = 0,
            status = this.status,
            createdAt = createdAt,
        )
}
