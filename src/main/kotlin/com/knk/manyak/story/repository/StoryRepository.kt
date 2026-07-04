package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.Story
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface StoryRepository : JpaRepository<Story, Long> {

    // KNK-447: 회원 서재(내 스토리 목록). 요청자 소유·미삭제만 생성 최신순으로 조회한다. limit은 Pageable로 상한을 건다.
    fun findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId: Long, pageable: Pageable): List<Story>
    // 소프트 삭제된 스토리(deleted_at IS NOT NULL)는 조회·삭제 대상에서 제외한다.
    fun findByIdAndDeletedAtIsNull(id: Long): Story?

    fun findAllByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<Story>

    // KNK-256: API 외부 식별자(public_id) 기준 조회. 순차 PK 열거(IDOR) 차단.
    // 삭제된 스토리 제외라 KNK-257(삭제 스토리 채팅 생성 차단)도 이 조회로 함께 해결된다.
    fun findByPublicIdAndDeletedAtIsNull(publicId: UUID): Story?

    fun findAllByPublicIdInAndDeletedAtIsNull(publicIds: Collection<UUID>): List<Story>

    /**
     * 게스트 스토리(user_id NULL)의 소유권을 원자적으로 클레임한다(KNK-389). 갱신한 행 수를 반환한다(1=성공, 0=이미 소유됨/선점됨).
     *
     * `WHERE user_id IS NULL` 조건을 DB가 갱신 시점에 재평가하므로, 동시 요청이 같은 행을 클레임해도 한 트랜잭션만 1을 받는다.
     */
    @Modifying
    @Query("UPDATE Story s SET s.userId = :userId WHERE s.publicId = :publicId AND s.userId IS NULL AND s.deletedAt IS NULL")
    fun claimByPublicId(@Param("publicId") publicId: UUID, @Param("userId") userId: Long): Int

    /**
     * public_id로 현재 소유자(user_id)를 조회한다. 클레임 실패(0건) 후 실제 소유자를 재확인하는 용도(동시 요청 멱등 판정).
     *
     * 엔티티 로드가 아닌 스칼라 조회라 영속성 컨텍스트 캐시를 우회해 DB의 최신 커밋 값을 읽는다.
     */
    @Query("SELECT s.userId FROM Story s WHERE s.publicId = :publicId")
    fun findUserIdByPublicId(@Param("publicId") publicId: UUID): Long?
}
