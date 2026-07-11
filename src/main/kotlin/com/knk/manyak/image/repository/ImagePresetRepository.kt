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
