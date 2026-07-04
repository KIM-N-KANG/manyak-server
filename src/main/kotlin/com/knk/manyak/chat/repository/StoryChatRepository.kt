package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface StoryChatRepository : JpaRepository<StoryChat, Long> {
    // 소프트 삭제된(deleted_at IS NOT NULL) 채팅은 조회에서 제외한다.
    fun findByPublicIdAndDeletedAtIsNull(publicId: UUID): StoryChat?

    fun findAllByPublicIdInAndDeletedAtIsNull(publicIds: Collection<UUID>): List<StoryChat>

    /**
     * 게스트 채팅(user_id NULL)의 소유권을 원자적으로 클레임한다(KNK-389). 갱신한 행 수를 반환한다(1=성공, 0=이미 소유됨/선점됨).
     *
     * `WHERE user_id IS NULL` 조건을 DB가 갱신 시점에 재평가하므로, 동시 요청이 같은 행을 클레임해도 한 트랜잭션만 1을 받는다.
     */
    @Modifying
    @Query("UPDATE StoryChat c SET c.userId = :userId WHERE c.publicId = :publicId AND c.userId IS NULL AND c.deletedAt IS NULL")
    fun claimByPublicId(@Param("publicId") publicId: UUID, @Param("userId") userId: Long): Int
}
