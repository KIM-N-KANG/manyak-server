package com.knk.manyak.story.service

import com.knk.manyak.story.entity.EndingSnapshot
import com.knk.manyak.story.entity.MainEventSnapshot
import com.knk.manyak.story.entity.StartSettingSnapshot
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryPublicSnapshot
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StoryPublicSnapshotRow
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StorySettingsSnapshot
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryPublicSnapshotRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.springframework.stereotype.Service

/**
 * 스토리의 "마지막 공개 버전" 스냅샷을 뜬다(KNK-1065).
 *
 * [capture]는 스토리의 **현재** 표시·생성 재료를 [StoryPublicSnapshot] 한 덩어리로 모은다. 두 곳에서 쓴다.
 * 1. [refresh] — 스토리가 공개(PUBLISHED∧PUBLIC) 상태로 저장될 때마다 `stories.last_public_snapshot`을 덮어쓴다.
 * 2. AI 턴 요청 조립의 **읽기 가능** 분기 — 라이브 값을 스냅샷과 같은 모양으로 만들어, 조립 코드가 공개·비공개
 *    두 경우에 같은 한 갈래로 흐르게 한다. 두 갈래로 나누면 한쪽만 고치는 순간 이 티켓이 막은 유출이 되살아난다.
 */
@Service
class StoryPublicSnapshotService(
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val storyPublicSnapshotRepository: StoryPublicSnapshotRepository,
) {

    /**
     * 스토리가 지금 공개 상태면 스냅샷을 현재 값으로 갱신한다. 아니면 아무것도 하지 않는다 —
     * 비공개 상태의 값은 감추려는 개작본이라 스냅샷에 들어가면 안 된다.
     *
     * "공개 → 비공개 전환 순간에만 찍기"를 쓰지 않는 이유: 전환 경로가 가시성 변경·상태 변경·삭제로 흩어져 있어
     * 하나만 빠뜨리면 스냅샷이 통째로 비어 유출이 그대로 남는다. 매 저장은 직렬화 한 번이라 싸고 로직이 한 곳이다.
     *
     * 호출부는 스토리 애그리거트 저장 트랜잭션 **끝**에서 부른다(자식 교체가 모두 끝난 뒤여야 한다).
     */
    fun refresh(story: Story) {
        if (story.deletedAt != null || !story.isPubliclyVisible()) {
            return
        }
        val captured = capture(story)
        // 스토리당 한 행(story_id가 PK)이라 있으면 덮고 없으면 만든다.
        val existing = storyPublicSnapshotRepository.findById(story.id).orElse(null)
        if (existing == null) {
            storyPublicSnapshotRepository.save(StoryPublicSnapshotRow(storyId = story.id, snapshot = captured))
        } else {
            existing.snapshot = captured
            storyPublicSnapshotRepository.save(existing)
        }
    }

    /** 스토리 하나의 마지막 공개 버전 스냅샷. 없으면 null(한 번도 공개된 적 없거나 백필 대상 밖). */
    fun findByStoryId(storyId: Long): StoryPublicSnapshot? =
        storyPublicSnapshotRepository.findById(storyId).orElse(null)?.snapshot

    /** 여러 스토리의 스냅샷을 한 번에 조회한다(서재·이용내역의 N+1 방지). 없는 스토리는 결과에 없다. */
    fun findAllByStoryIds(storyIds: Collection<Long>): Map<Long, StoryPublicSnapshot> {
        if (storyIds.isEmpty()) {
            return emptyMap()
        }
        return storyPublicSnapshotRepository.findAllById(storyIds).associate { it.storyId to it.snapshot }
    }

    /**
     * **턴 조립 전용 부분 캡처**: 채팅이 고른 시작 설정 하나와 그 엔딩만 뜬다(PR #224 Codex P2).
     *
     * [capture]는 스토리의 **모든** 시작 설정과 각 추천 입력·엔딩 본문을 읽는다. 스냅샷을 쓰는 쪽에서는 한 번
     * 뜨고 끝이지만 턴 조립은 **매 턴** 도는 경로이고 시작 설정 개수에 상한이 없어, 조립 코드를 한 갈래로
     * 유지하려고 치르는 값이 너무 커진다. 그래서 읽기 가능 분기는 필요한 만큼만 읽는다 — 모양은 그대로라
     * 조립 코드는 여전히 한 갈래다.
     *
     * 추천 입력은 담지 않는다: 턴 요청(`ChatTurnAiRequest`)에 실리지 않는다.
     */
    fun captureTurnMaterial(story: Story, startSettingId: Long?): StoryPublicSnapshot {
        val startSetting = startSettingId?.let { storyStartSettingRepository.findById(it).orElse(null) }
        return StoryPublicSnapshot(
            title = story.title,
            thumbnailImageKey = story.thumbnailImageKey,
            thumbnailImageUrl = story.thumbnailImageUrl,
            genre = story.genre,
            storySettings = storySettingRepository.findByStoryId(story.id).toSnapshot(),
            startSettings = listOfNotNull(
                startSetting?.let {
                    StartSettingSnapshot(
                        id = it.id,
                        name = it.name,
                        prologue = it.prologue,
                        startSituation = it.startSituation,
                        endings = storyEndingRepository
                            .findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(it.id)
                            .map(::toSnapshot),
                    )
                },
            ),
            mainEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id).map(::toSnapshot),
        )
    }

    /** 스토리의 현재 표시·생성 재료를 스냅샷 모양으로 모은다. */
    fun capture(story: Story): StoryPublicSnapshot {
        val setting = storySettingRepository.findByStoryId(story.id)
        val startSettings = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id)
        val startSettingIds = startSettings.map { it.id }
        // 추천 입력·엔딩은 시작 설정별 목록이라 각각 한 번의 IN 조회로 받아 그룹핑한다(N+1 방지).
        val inputsByStartSettingId = if (startSettingIds.isEmpty()) {
            emptyMap()
        } else {
            storySuggestedInputRepository
                .findByStartSettingIdInOrderByStartSettingIdAscInputOrderAsc(startSettingIds)
                .groupBy({ it.startSetting.id }, { it.inputText })
        }
        // 활성 엔딩만 담는다. 레거시(enabled=false) 행은 새 컬럼이 NULL이라 엔티티로 실체화하면 NPE가 나고
        // ([StoryEndingRepository] 주석), 턴 후보·상세 조회도 이미 활성만 본다 — 라이브 경로와 결과가 같다.
        val endingsByStartSettingId = if (startSettingIds.isEmpty()) {
            emptyMap()
        } else {
            storyEndingRepository
                .findByStartSettingIdInAndEnabledTrueOrderByStartSettingIdAscSortOrderAsc(startSettingIds)
                .groupBy { it.startSetting.id }
        }

        return StoryPublicSnapshot(
            title = story.title,
            thumbnailImageKey = story.thumbnailImageKey,
            thumbnailImageUrl = story.thumbnailImageUrl,
            genre = story.genre,
            storySettings = setting.toSnapshot(),
            startSettings = startSettings.map { startSetting ->
                StartSettingSnapshot(
                    id = startSetting.id,
                    name = startSetting.name,
                    prologue = startSetting.prologue,
                    startSituation = startSetting.startSituation,
                    suggestedInputs = inputsByStartSettingId[startSetting.id].orEmpty(),
                    endings = endingsByStartSettingId[startSetting.id].orEmpty().map(::toSnapshot),
                )
            },
            mainEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id).map(::toSnapshot),
        )
    }

    private fun StorySetting?.toSnapshot() = StorySettingsSnapshot(
        worldSetting = this?.worldSetting,
        characterSetting = this?.characterSetting,
        userRoleSetting = this?.userRoleSetting,
        ruleSetting = this?.ruleSetting,
    )

    private fun toSnapshot(ending: StoryEnding) = EndingSnapshot(
        id = ending.id,
        name = ending.name,
        minTurns = ending.minTurns,
        achievementCondition = ending.achievementCondition,
        epilogue = ending.epilogue,
    )

    private fun toSnapshot(event: StoryMainEvent) = MainEventSnapshot(
        id = event.id,
        name = event.name,
        description = event.description,
        keySentence = event.keySentence,
    )
}
