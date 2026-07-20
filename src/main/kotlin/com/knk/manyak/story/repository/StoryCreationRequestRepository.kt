package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationRequest
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface StoryCreationRequestRepository : JpaRepository<StoryCreationRequest, Long> {
    fun findByRequestId(requestId: UUID): StoryCreationRequest?

    /** 재요청 판정을 직렬화하려 행을 비관적 락으로 조회한다(동시 FAILED 재실행이 둘 다 통과하지 않도록). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM StoryCreationRequest r WHERE r.requestId = :requestId")
    fun findByRequestIdForUpdate(requestId: UUID): StoryCreationRequest?
}
