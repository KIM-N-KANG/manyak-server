package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChat
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface StoryChatRepository : JpaRepository<StoryChat, Long> {

    /**
     * 채팅 행을 비관적 쓰기 락으로 조회한다. 이어쓰기(append)와 재생성(replace)이 이 락으로 채팅 단위로 직렬화된다.
     * 재생성은 마지막 ASSISTANT를 제자리 교체해 message_order 유니크로 자연 직렬화되지 않으므로, 동시 재생성이
     * 마지막 턴 검사·교체·regenerated_count 증가를 각자 수행하는 것(중복 과금·lost update)과, 이어쓰기의 미커밋 새 턴을
     * 못 본 채 낡은 마지막 턴을 교체하는 것(재생성 vs 이어쓰기 경합)을 막는다. 두 저장 경로 모두 검사·삽입 전에 이 락을 잡는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM StoryChat c WHERE c.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): StoryChat?

    // KNK-447: 회원 서재(내 채팅 목록). 요청자 소유·미삭제만 최근 활동순(updatedAt)으로 조회한다. limit은 Pageable로 상한을 건다.
    fun findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(userId: Long, pageable: Pageable): List<StoryChat>
    // 소프트 삭제된(deleted_at IS NOT NULL) 채팅은 조회에서 제외한다.
    fun findByPublicIdAndDeletedAtIsNull(publicId: UUID): StoryChat?

    /**
     * 채팅 행을 public_id로 비관적 쓰기 락으로 조회한다(미삭제만). 삭제의 소유권 검사와 deletedAt 기록 사이에
     * 마이그레이션 클레임([claimByPublicId])이 끼어들어 방금 회원 소유가 된 채팅을 익명 삭제가 지우는 경쟁을 막는다
     * — 소유권 판정과 삭제 쓰기를 채팅 행 단위로 직렬화한다(KNK-69).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM StoryChat c WHERE c.publicId = :publicId AND c.deletedAt IS NULL")
    fun findByPublicIdAndDeletedAtIsNullForUpdate(@Param("publicId") publicId: UUID): StoryChat?

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
