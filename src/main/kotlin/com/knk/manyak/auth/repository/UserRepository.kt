package com.knk.manyak.auth.repository

import com.knk.manyak.auth.entity.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface UserRepository : JpaRepository<User, Long> {
    // API 외부 식별자(public_id) 기준 조회. 순차 PK 열거(IDOR) 차단.
    fun findByPublicId(publicId: UUID): User?

    // 초대 코드로 초대자를 역해석한다(POST /auth/login/google의 inviteCode → 초대자 User). 없으면 null(무시).
    fun findByInviteCode(inviteCode: String): User?

    // 초대 코드 지연 발급 시 후보의 유일성을 확인한다(유니크 제약이 최종 방어, 이건 재시도용 사전 점검).
    fun existsByInviteCode(inviteCode: String): Boolean

    /**
     * 사용자 행을 비관적 쓰기 락으로 조회한다. 초대 코드 지연 발급에서 같은 사용자의 동시 GET /invite가
     * 서로 다른 코드로 덮어쓰지 않도록 발급을 직렬화한다(먼저 잡은 쪽이 발급하면 뒤는 그 값을 읽는다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): User?

    /**
     * 회원 체험 스냅샷 완료를 기록한다(스펙 §4-3-7 B13, KNK-504). 아직 미스냅샷(NULL)인 계정만 채워, 동시 첫
     * 로그인 경합·재시도에도 최초 1회만 유효하게 남는다(이미 채워진 값은 덮지 않는다). 갱신 행 수를 반환한다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.memberTrialSeededAt = :seededAt WHERE u.id = :id AND u.memberTrialSeededAt IS NULL")
    fun markMemberTrialSeeded(@Param("id") id: Long, @Param("seededAt") seededAt: Instant): Int
}
