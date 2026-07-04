package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChat
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface StoryChatRepository : JpaRepository<StoryChat, Long> {

    // KNK-447: 회원 서재(내 채팅 목록). 요청자 소유·미삭제만 최근 활동순(updatedAt)으로 조회한다. limit은 Pageable로 상한을 건다.
    fun findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(userId: Long, pageable: Pageable): List<StoryChat>
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

    /**
     * public_id로 현재 소유자(user_id)를 조회한다. 클레임 실패(0건) 후 실제 소유자를 재확인하는 용도(동시 요청 멱등 판정).
     *
     * 엔티티 로드가 아닌 스칼라 조회라 영속성 컨텍스트 캐시를 우회해 DB의 최신 커밋 값을 읽는다.
     */
    @Query("SELECT c.userId FROM StoryChat c WHERE c.publicId = :publicId")
    fun findUserIdByPublicId(@Param("publicId") publicId: UUID): Long?
}
