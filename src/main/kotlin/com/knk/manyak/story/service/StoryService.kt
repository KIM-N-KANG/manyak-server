package com.knk.manyak.story.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.global.security.isOwnerAccessAllowed
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.service.ImageUrlResolver
import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.LorebookListItemResponse
import com.knk.manyak.story.dto.LorebookResponse
import com.knk.manyak.story.dto.StoryAuthorResponse
import com.knk.manyak.story.dto.StoryCharacterResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.dto.toMainEventResponse
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryLike
import com.knk.manyak.story.entity.StoryReport
import com.knk.manyak.story.entity.StoryReportReason
import com.knk.manyak.story.entity.StoryLorebook
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryLikeRepository
import com.knk.manyak.story.repository.StoryReportRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.UserStoryEndingReachRepository
import org.springframework.beans.factory.annotation.Value
import com.knk.manyak.story.report.StoryReportedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
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
    private val storyLikeRepository: StoryLikeRepository,
    private val storyReportRepository: StoryReportRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val suspensionGuard: SuspensionGuard,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyCharacterRepository: StoryCharacterRepository,
    private val userStoryEndingReachRepository: UserStoryEndingReachRepository,
    private val storyChatRepository: StoryChatRepository,
    private val imageUrlResolver: ImageUrlResolver,
    private val userRepository: UserRepository,
    // 마냑 공식 계정(오리지널 스토리 소유자)의 user public_id. 미설정(빈 값)이면 오리지널 목록은 빈 배열이다(KNK-975).
    @Value("\${manyak.official-user-public-id:}") officialUserPublicId: String,
) {

    // 잘못된 UUID는 기동 시점에 실패시켜 조용한 빈 목록 오설정을 막는다.
    private val officialUserPublicId: UUID? =
        officialUserPublicId.takeIf { it.isNotBlank() }?.let(UUID::fromString)

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

    /**
     * 마냑 오리지널 스토리 목록(KNK-975). 공식 계정 소유의 공개(PUBLISHED∧PUBLIC) 스토리를 등록순으로 반환한다.
     * 피드·검색이 나오기 전까지 홈의 오리지널 섹션이 소비하며, 공식 계정 미설정 환경은 빈 목록이다.
     */
    @Transactional(readOnly = true)
    fun getOriginalStories(): List<StorySummaryResponse> {
        val publicId = officialUserPublicId ?: return emptyList()
        val official = userRepository.findByPublicId(publicId) ?: return emptyList()
        return storyRepository
            .findByUserIdAndStatusAndVisibilityAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                official.id,
                StoryStatus.PUBLISHED,
                StoryVisibility.PUBLIC,
            )
            .toSummaryResponses()
    }

    @Transactional(readOnly = true)
    fun getStoryDetail(storyId: String, userId: Long?): StoryDetailResponse {
        // 공개 상세 조회는 공개(PUBLISHED∧PUBLIC) 스토리만, 단 소유자는 자신의 비공개·초안도 볼 수 있다(KNK-401).
        val story = resolveReadableStory(storyId, userId)

        // 내부 PK(story.id)로 자식 데이터를 조회한다. 외부 식별자(public_id)는 응답에만 노출한다.
        // 시작 설정 복수화(KNK-515): 등록 순서로 전부 싣고, 추천 입력·엔딩은 각 시작 설정에 종속시킨다.
        val startSettings = startSettingResponseAssembler.assemble(story.id)

        val lorebooks = storyLorebookRepository.findByStoryIdOrderBySortOrderAscIdAsc(story.id)
            .map { it.toLorebookResponse() }
        val mainEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)
            .map { it.toMainEventResponse() }
        // 인물은 저장 순서(= 컴파일 응답 순서)로 싣는다(KNK-1058). 이미지 생성에 실패한 인물도 imageUrl null로 포함해
        // 프론트가 인물 구성을 그대로 보여줄 수 있게 한다(채팅 요청 매핑과 달리 URL 없는 인물을 거르지 않는다).
        val characters = storyCharacterRepository.findByStoryIdOrderByIdAsc(story.id)
            .map { StoryCharacterResponse(name = it.name, imageUrl = it.imageUrl) }
        // 요청 회원이 이 스토리에서 도달한 엔딩 이름 집계(스펙 §4-3-10). 게스트(userId null)는 빈 배열.
        // 저장도 노출도 이름 기준이다(V70) — 프론트는 엔딩 목록과 이름으로 상관한다(KNK-462).
        val reachedEndings = resolveReachedEndingNames(userId, story.id, startSettings)

        return StoryDetailResponse(
            id = story.publicId.toString(),
            // 생성 표지가 있으면 그것을, 없으면 프리셋 키로 조합한다(2단 폴백은 리졸버 소유, KNK-1069).
            thumbnailUrl = imageUrlResolver.thumbnailUrlFor(story.thumbnailImageUrl, story.thumbnailImageKey),
            title = story.title,
            oneLineIntro = story.oneLineIntro.orEmpty(),
            description = story.description,
            genres = story.toGenreNames(),
            hashtags = emptyList(),
            author = resolveAuthor(story.userId),
            turnCount = storyChatRepository.sumCurrentTurnByStoryId(story.id),
            likeCount = storyLikeRepository.countByStoryId(story.id),
            // 소유 판단은 서버가 내려준다(KNK-1018). 게스트(userId null)는 항상 false — 소유권 게이트(§4-5)와 달리
            // 게스트 스토리의 게스트 요청도 false다(게스트끼리 구분 불가라 메뉴 노출 판단으로 부적합).
            isOwner = userId != null && userId == story.userId,
            // 좋아요 여부도 서버 판단이다(KNK-1017). 게스트는 좋아요할 수 없으므로 항상 false다.
            isLiked = userId != null && storyLikeRepository.existsByUserIdAndStoryId(userId, story.id),
            startSettings = startSettings,
            visibility = story.visibility,
            status = story.status,
            lorebooks = lorebooks,
            mainEvents = mainEvents,
            characters = characters,
            reachedEndings = reachedEndings,
            createdAt = story.createdAt,
        )
    }

    /**
     * 회원이 한 스토리에서 도달한 엔딩 이름을 표시 순서로 반환한다. 게스트는 빈 목록.
     *
     * 집계의 정본 식별자가 이름이므로(V70·V71) 이름을 그대로 읽는다. 표시 순서는 [startSettings]에 실린 **현재**
     * 엔딩 순서를 따른다 — 제작자가 지웠던 엔딩을 **같은 이름으로 다시 만들면 여기서 자연히 다시 이어진다.**
     * 지금 스토리에 없는 이름(교체돼 사라진 엔딩)은 순서를 알 수 없어 뒤에 이름순으로 붙인다. 도달 기록은
     * 사라지지 않으므로 화면에서 빠지지 않는다.
     */
    private fun resolveReachedEndingNames(
        userId: Long?,
        storyId: Long,
        startSettings: List<StoryStartSettingResponse>,
    ): List<String> {
        if (userId == null) {
            return emptyList()
        }
        // 이름은 NOT NULL이고 (회원, 스토리, 이름) 유니크라(V71) 그대로 읽으면 된다. 이름이 비어 있는 행을
        // ending_id로 해소하던 폴백과 중복 제거는 그 두 제약이 대신하므로 함께 걷었다.
        val reachedNames = userStoryEndingReachRepository.findByUserIdAndStoryId(userId, storyId)
            .map { it.endingNameSnapshot }
        if (reachedNames.isEmpty()) {
            return emptyList()
        }
        // 시작 설정 순서 → 그 안의 엔딩 순서(assemble이 sort_order로 정렬해 준다).
        // 동명 엔딩은 **첫 등장**이 이긴다 — associate는 마지막 값을 남기므로 putIfAbsent로 첫 인덱스를 지킨다.
        val orderByName = HashMap<String, Int>()
        startSettings.flatMap { it.endings }.forEachIndexed { index, ending ->
            orderByName.putIfAbsent(ending.name, index)
        }
        return reachedNames.sortedWith(compareBy({ orderByName[it] ?: Int.MAX_VALUE }, { it }))
    }

    /**
     * 스토리 좋아요 등록(스펙 §4-3-1, KNK-1017). 이미 좋아요한 스토리의 재등록도 성공(204)이다 — 멱등은
     * `(user_id, story_id)` UNIQUE가 보장하므로 사전 조회 없이 삽입하고 유니크 위반을 흡수한다(동시 등록 경합 포함).
     *
     * **의도적으로 트랜잭션을 열지 않는다**([AccountLinkService.link]와 같은 이유): 바깥 트랜잭션이 있으면
     * 유니크 위반을 잡는 순간 그 트랜잭션이 rollback-only로 오염돼, 경합을 흡수하고 응답하려는 순간 커밋이 터진다.
     */
    fun like(storyId: String, userId: Long) {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        val story = resolveReadableStory(storyId, userId)
        try {
            storyLikeRepository.saveAndFlush(StoryLike(userId = userId, storyId = story.id))
        } catch (ignored: DataIntegrityViolationException) {
            // 이미 좋아요한 스토리(또는 동시 등록 경합). 계약대로 멱등하게 통과한다.
        }
    }

    /**
     * 스토리 신고 등록(스펙 §4-3-1 스토리 신고, KNK-1020).
     * 같은 회원의 같은 스토리 재신고는 멱등 흡수(201)하고 알림도 다시 보내지 않는다.
     * 좋아요와 같은 이유로 트랜잭션을 열지 않는다(유니크 위반 흡수 시 rollback-only 오염 방지).
     */
    fun report(storyId: String, userId: Long, reason: StoryReportReason, detail: String?) {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        val story = resolveReadableStory(storyId, userId)
        val saved = try {
            storyReportRepository.saveAndFlush(
                StoryReport(userId = userId, storyId = story.id, reason = reason, detail = detail),
            )
        } catch (ignored: DataIntegrityViolationException) {
            // 이미 신고한 스토리(또는 동시 등록 경합). 멱등하게 통과하고 알림은 중복 발송하지 않는다.
            return
        }
        eventPublisher.publishEvent(
            StoryReportedEvent(
                reportId = saved.id,
                storyPublicId = story.publicId.toString(),
                storyTitle = story.title,
                reason = saved.reason,
                detail = saved.detail,
                createdAt = saved.createdAt,
            ),
        )
    }

    /** 스토리 좋아요 취소(스펙 §4-3-1, KNK-1017). 좋아요가 없는 스토리의 취소도 성공(204)이다. */
    fun unlike(storyId: String, userId: Long) {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        val story = resolveReadableStory(storyId, userId)
        storyLikeRepository.deleteByUserIdAndStoryId(userId, story.id)
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

    /**
     * [resolveStory]에 읽기 가시성 게이트([Story.isReadableBy])를 더해 조회한다. 읽을 수 없으면 존재하지 않는
     * 것과 동일하게 404로 통일한다(존재 여부 비노출). 상세 조회·좋아요 등록·취소가 같은 게이트를 공유한다.
     */
    private fun resolveReadableStory(publicId: String, userId: Long?): Story {
        val story = resolveStory(publicId)
        if (!story.isReadableBy(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }
        return story
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

    /** 작성자 카드 정보. 내부 PK는 노출하지 않으므로 id는 항상 null이다(IDOR 방지 원칙과 동일 취지). */
    private fun resolveAuthor(userId: Long?): StoryAuthorResponse? =
        userId
            ?.let { userRepository.findById(it).orElse(null) }
            ?.let { StoryAuthorResponse(id = null, nickname = it.nickname, profileImageUrl = it.profileImageUrl) }

    /** 스토리 목록을 카드 응답으로 매핑한다. turnCount·author는 한 번의 배치 조회로 채운다(N+1 방지). */
    private fun List<Story>.toSummaryResponses(): List<StorySummaryResponse> {
        if (isEmpty()) {
            return emptyList()
        }
        val turnCountByStoryId = storyChatRepository.sumCurrentTurnByStoryIds(map { it.id })
            .associate { it.storyId to it.turnCount }
        val likeCountByStoryId = storyLikeRepository.countByStoryIds(map { it.id })
            .associate { it.storyId to it.likeCount }
        val authorByUserId = userRepository.findAllById(mapNotNull { it.userId }.distinct())
            .associate { it.id to StoryAuthorResponse(id = null, nickname = it.nickname, profileImageUrl = it.profileImageUrl) }
        return map {
            it.toSummaryResponse(
                turnCount = turnCountByStoryId[it.id] ?: 0,
                likeCount = likeCountByStoryId[it.id] ?: 0,
                author = it.userId?.let(authorByUserId::get),
            )
        }
    }

    private fun Story.toSummaryResponse(
        turnCount: Long,
        likeCount: Long,
        author: StoryAuthorResponse? = null,
    ): StorySummaryResponse =
        StorySummaryResponse(
            id = publicId.toString(),
            // 목록 카드는 축소 변형을 쓴다(상세만 원본 — 스펙 §4-3-9 반응형 변형). 단 생성 표지는 축소본이
            // 없어 원본 URL이 그대로 실린다(KNK-1069, 무게는 후속 과제).
            thumbnailUrlSm = imageUrlResolver.thumbnailSmUrlFor(thumbnailImageUrl, thumbnailImageKey),
            title = title,
            oneLineIntro = oneLineIntro.orEmpty(),
            genres = toGenreNames(),
            author = author,
            turnCount = turnCount,
            likeCount = likeCount,
            status = this.status,
            createdAt = createdAt,
        )
}
