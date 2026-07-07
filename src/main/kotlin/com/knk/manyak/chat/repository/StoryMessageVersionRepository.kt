package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryMessageVersion
import org.springframework.data.jpa.repository.JpaRepository

interface StoryMessageVersionRepository : JpaRepository<StoryMessageVersion, Long> {
    // 다음 versionNumber 계산용. append-only라 삭제가 없어 count + 1이 다음 순번이다.
    fun countByMessageId(messageId: Long): Long

    // 보관 순서대로 이력을 조회한다(분석·검증용).
    fun findByMessageIdOrderByVersionNumberAsc(messageId: Long): List<StoryMessageVersion>
}
