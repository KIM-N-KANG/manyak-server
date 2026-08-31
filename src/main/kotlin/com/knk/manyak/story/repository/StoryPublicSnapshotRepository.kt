package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryPublicSnapshotRow
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 마지막 공개 버전 스냅샷 조회·저장(KNK-1065). 식별자는 `story_id`다.
 *
 * **스토리 목록 경로는 이 리포지터리를 타지 않는다.** 스냅샷은 스토리를 읽을 수 없는 요청에서만 필요하므로,
 * 호출부가 그때만 조회한다(`StoryPublicSnapshotIsolationTests`가 회귀를 막는다).
 */
interface StoryPublicSnapshotRepository : JpaRepository<StoryPublicSnapshotRow, Long>
