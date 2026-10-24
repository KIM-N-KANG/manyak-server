package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationRequest
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface StoryCreationRequestRepository : JpaRepository<StoryCreationRequest, Long> {
    fun findByRequestId(requestId: UUID): StoryCreationRequest?
    fun findByUserIdAndStatus(userId: Long, status: com.knk.manyak.story.entity.StoryCreationRequestStatus): List<StoryCreationRequest>

    /** 사용자 잠금을 먼저 잡기 위한 비잠금 소유자 조회. 엔티티를 미리 로드하지 않는다. */
    @Query("SELECT r.userId FROM StoryCreationRequest r WHERE r.id = :id")
    fun findUserIdById(id: Long): Long?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM StoryCreationRequest r WHERE r.id = :id")
    fun findByIdForUpdate(id: Long): StoryCreationRequest?

    /** 재요청 판정을 직렬화하려 행을 비관적 락으로 조회한다(동시 FAILED 재실행이 둘 다 통과하지 않도록). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM StoryCreationRequest r WHERE r.requestId = :requestId")
    fun findByRequestIdForUpdate(requestId: UUID): StoryCreationRequest?
}
