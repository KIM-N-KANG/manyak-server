package com.knk.manyak.image.repository

import com.knk.manyak.image.entity.ImagePreset
import com.knk.manyak.image.entity.ImagePresetType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ImagePresetRepository : JpaRepository<ImagePreset, Long> {

    /**
     * 주어진 장르 태그명이 붙은 활성 이미지의 키를 등재 순서로 반환한다.
     *
     * 장르명은 태그 마스터를 거쳐 저장되므로 스토리의 장르 문자열과 동등 비교로 매칭된다.
     */
    @Query(
        """
        select p.imageKey from ImagePreset p
        join p.genres g
        where p.type = :type
          and p.deactivatedAt is null
          and g.name = :genreName
        order by p.id
        """,
    )
    fun findActiveImageKeysByTypeAndGenreName(
        @Param("type") type: ImagePresetType,
        @Param("genreName") genreName: String,
    ): List<String>

    /** 주어진 키 중 활성인 프리셋만 반환한다. 카탈로그에 없거나 비활성인 키는 결과에서 빠진다. */
    fun findByImageKeyInAndDeactivatedAtIsNull(imageKeys: Collection<String>): List<ImagePreset>

    /**
     * 주어진 키의 프리셋을 활성 여부와 무관하게 반환한다.
     *
     * 지난 턴 `images[]` 재구성은 "그 턴의 확정 시각 시점" 상태를 봐야 하므로, 지금 활성인지가 아니라
     * 등록 시각·비활성 시각을 함께 받아 애플리케이션에서 턴별로 비교한다(§4-3-9 재구성 불변).
     */
    fun findByImageKeyIn(imageKeys: Collection<String>): List<ImagePreset>

    /**
     * 스토리에 연결된 배경 후보 중 지금 활성인 것만 연결 순서로 반환한다(매 턴 AI 요청 재료).
     *
     * 비활성 이미지는 전달에서 제외한다 — 내린 다음 턴부터 즉시 노출이 멈춰야 하기 때문이다(§4-3-9 비활성 적용 범위).
     */
    @Query(
        """
        select p from StoryImage si, ImagePreset p
        where si.imageKey = p.imageKey
          and si.storyId = :storyId
          and p.type = com.knk.manyak.image.entity.ImagePresetType.BACKGROUND
          and p.deactivatedAt is null
        order by si.id
        """,
    )
    fun findActiveBackgroundCandidates(@Param("storyId") storyId: Long): List<ImagePreset>

    /** 장르를 가리지 않는 활성 이미지의 키를 등재 순서로 반환한다(장르 매칭 실패 시 폴백). */
    @Query(
        """
        select p.imageKey from ImagePreset p
        where p.type = :type
          and p.deactivatedAt is null
        order by p.id
        """,
    )
    fun findActiveImageKeysByType(@Param("type") type: ImagePresetType): List<String>
}
