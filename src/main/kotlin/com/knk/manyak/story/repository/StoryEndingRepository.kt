package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryEnding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

interface StoryEndingRepository : JpaRepository<StoryEnding, Long> {

    // 시작 설정의 엔딩을 표시 순서로 조회한다(상세 응답, 교체 저장 후 반환).
    fun findByStartSettingIdOrderBySortOrderAsc(startSettingId: Long): List<StoryEnding>

    // 교체 저장(PUT)에서 기존 엔딩을 먼저 지운다. 반환값은 삭제한 행 수.
    @Transactional
    fun deleteByStartSettingId(startSettingId: Long): Long
}
