package com.knk.manyak.story.service

import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 채팅(스토리 플레이)이 읽는 스토리 값(KNK-2055). chat이 story 리포지토리·엔티티를 직접 참조하지 않도록 이 클래스가
 * 대신 읽어 읽기 모델로 돌려준다. **chat이 실제로 쓰는 조회만 둔다.**
 *
 * 지켜야 할 것:
 * - **`@Transactional`을 붙이지 않는다.** 채팅 읽기 경로와 AI 요청 조립은 게이트(스토리)와 자식 행을 한 REPEATABLE_READ
 *   스냅샷에서 읽어야 한다(KNK-1059·1064). 붙이지 않아야 호출자 트랜잭션에 그대로 합류한다. 트랜잭션 밖 호출은
 *   리포지토리 기본 트랜잭션을 탄다(이전과 같다).
 * - **소프트 삭제를 거르지 않는다.** id 조회는 삭제된 스토리도 돌려주고, 삭제 판정은 [StoryView.currentMetadataVisible]이
 *   한다. 채팅 서재·상세는 삭제된 스토리면 스냅샷으로 떨어져야 하기 때문이다. 삭제를 거르는 것은 채팅 시작용
 *   [findViewByPublicId]뿐이다.
 * - 기존 리포지토리 메서드를 그대로 부른다. 쿼리 수를 늘리지 않는다.
 */
@Component
class StoryPlayReader(
    private val storyRepository: StoryRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
) {

    /** 삭제되지 않은 스토리를 공개 식별자로 읽는다(채팅 시작). */
    fun findViewByPublicId(publicId: UUID, requesterId: Long?): StoryView? =
        storyRepository.findByPublicIdAndDeletedAtIsNull(publicId)?.let { StoryView.of(it, requesterId) }

    /** 삭제된 스토리도 돌려준다. */
    fun findView(storyId: Long, requesterId: Long?): StoryView? =
        storyRepository.findById(storyId).orElse(null)?.let { StoryView.of(it, requesterId) }

    /** 삭제된 스토리도 돌려준다. 없는 id는 결과에서 빠진다. */
    fun findViews(storyIds: Collection<Long>, requesterId: Long?): Map<Long, StoryView> =
        storyRepository.findAllById(storyIds).associate { it.id to StoryView.of(it, requesterId) }

    /** 삭제된 스토리도 돌려준다. */
    fun findPublicId(storyId: Long): UUID? = storyRepository.findById(storyId).orElse(null)?.publicId

    /** 스토리의 첫(기본) 시작 설정. */
    fun findFirstStartSetting(storyId: Long): StartSettingView? =
        storyStartSettingRepository.findFirstByStoryIdOrderByIdAsc(storyId)?.let(StartSettingView::of)

    /** 공개 식별자로 찾은 시작 설정이 [storyId] 소속일 때만 돌려준다. */
    fun findStartSettingOfStory(storyId: Long, publicId: UUID): StartSettingView? =
        storyStartSettingRepository.findByPublicId(publicId)
            ?.takeIf { it.story.id == storyId }
            ?.let(StartSettingView::of)

    fun findStartSetting(startSettingId: Long): StartSettingView? =
        storyStartSettingRepository.findById(startSettingId).orElse(null)?.let(StartSettingView::of)

    /** input_order 오름차순. */
    fun findSuggestedInputs(startSettingId: Long): List<String> =
        storySuggestedInputRepository.findByStartSettingIdOrderByInputOrderAsc(startSettingId).map { it.inputText }

    /** 엔딩 id → 이름. 없는 id는 빠진다. */
    fun findEndingNames(endingIds: Collection<Long>): Map<Long, String> =
        storyEndingRepository.findAllById(endingIds).associate { it.id to it.name }

    fun findMainEventName(mainEventId: Long): String? = storyMainEventRepository.findById(mainEventId).orElse(null)?.name

    /** 이름으로 찾은 **현재** 활성 엔딩 행의 id. */
    fun findLiveEndingId(startSettingId: Long, name: String): Long? =
        storyEndingRepository.findFirstByStartSettingIdAndNameAndEnabledTrue(startSettingId, name)?.id

    /** 이름으로 찾은 **현재** 주요 사건 행의 id. */
    fun findLiveMainEventId(storyId: Long, name: String): Long? =
        storyMainEventRepository.findFirstByStoryIdAndName(storyId, name)?.id

    /** 이 스토리를 만든 간편 제작 세션의 creation_id. 일반 제작 스토리는 null. */
    fun findCreationId(storyId: Long): UUID? =
        storyCreationSessionRepository.findFirstByStoryIdOrderByIdAsc(storyId)?.storylineRequestId
}

/**
 * 채팅이 읽는 스토리 값. 게이트 판정은 요청자를 받아 엔티티 메서드로 계산해 싣는다 — 판정 주체가 호출부마다 다르다
 * (읽기 경로는 요청자, AI 요청 조립은 채팅 소유자).
 */
data class StoryView(
    val id: Long,
    val publicId: UUID,
    val userId: Long?,
    val title: String,
    val protagonistName: String?,
    val thumbnailImageUrl: String?,
    val thumbnailImageKey: String?,
    val thumbnailModerationStatus: ImageModerationStatus,
    /** [Story.isReadableBy] */
    val readable: Boolean,
    /** [Story.isPubliclyVisible] */
    val publiclyVisible: Boolean,
    /** [Story.isCurrentMetadataVisibleTo] */
    val currentMetadataVisible: Boolean,
) {
    companion object {
        fun of(story: Story, requesterId: Long?) = StoryView(
            id = story.id,
            publicId = story.publicId,
            userId = story.userId,
            title = story.title,
            protagonistName = story.protagonistName,
            thumbnailImageUrl = story.thumbnailImageUrl,
            thumbnailImageKey = story.thumbnailImageKey,
            thumbnailModerationStatus = story.thumbnailModerationStatus,
            readable = story.isReadableBy(requesterId),
            publiclyVisible = story.isPubliclyVisible(),
            currentMetadataVisible = story.isCurrentMetadataVisibleTo(requesterId),
        )
    }
}

data class StartSettingView(val id: Long, val publicId: UUID, val prologue: String?) {
    companion object {
        fun of(setting: StoryStartSetting) = StartSettingView(setting.id, setting.publicId, setting.prologue)
    }
}
