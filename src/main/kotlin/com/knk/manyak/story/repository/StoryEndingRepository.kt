package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryEnding
import org.springframework.data.jpa.repository.JpaRepository

interface StoryEndingRepository : JpaRepository<StoryEnding, Long> {

    // 시작 설정의 활성 엔딩을 표시 순서로 조회한다(상세 응답). 레거시(enabled=false) 행은 새 컬럼이 NULL이라
    // 엔티티로 실체화하면 NPE가 나므로 조회 단계에서 제외한다(§4-3-10 레거시 비활성 보존).
    fun findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(startSettingId: Long): List<StoryEnding>
}
