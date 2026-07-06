package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryEnding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StoryEndingRepository : JpaRepository<StoryEnding, Long> {

    // 시작 설정의 활성 엔딩을 표시 순서로 조회한다(상세 응답). 레거시(enabled=false) 행은 새 컬럼이 NULL이라
    // 엔티티로 실체화하면 NPE가 나므로 조회 단계에서 제외한다(§4-3-10 레거시 비활성 보존).
    fun findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(startSettingId: Long): List<StoryEnding>

    // 수정(PATCH)의 엔딩 전체 교체에서 기존 엔딩을 지운다. 레거시(enabled=false)까지 함께 지워
    // (start_setting_id, sort_order) 유니크 충돌을 피한다 — 사용자가 새 엔딩을 재등록하면 레거시는 대체된다(§4-3-8).
    //
    // 파생 deleteBy는 대상 엔티티를 먼저 로드하는데, 레거시 행은 새 컬럼이 NULL이라 실체화 시 NPE가 난다.
    // 벌크 DELETE로 엔티티를 인스턴스화하지 않고 지운다. flushAutomatically로 선행 쓰기를 먼저 반영해 즉시 삭제된다.
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM StoryEnding e WHERE e.startSetting.id = :startSettingId")
    fun deleteByStartSettingId(@Param("startSettingId") startSettingId: Long)
}
