package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StorySuggestedInput
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StorySuggestedInputRepository : JpaRepository<StorySuggestedInput, Long> {
    fun findByStartSettingIdOrderByInputOrderAsc(startSettingId: Long): List<StorySuggestedInput>

    // 여러 시작 설정의 추천 입력을 한 번에 조회한다(상세 응답 복수화 N+1 방지, KNK-515). 호출부가 시작 설정별로 그룹핑한다.
    fun findByStartSettingIdInOrderByStartSettingIdAscInputOrderAsc(
        startSettingIds: Collection<Long>,
    ): List<StorySuggestedInput>

    // 수정(PATCH)의 추천 입력 전체 교체·시작 설정 삭제에서 기존 행을 지운다. 벌크 DELETE로 즉시 반영해 재삽입과 충돌하지 않는다.
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM StorySuggestedInput s WHERE s.startSetting.id = :startSettingId")
    fun deleteByStartSettingId(@Param("startSettingId") startSettingId: Long)
}
