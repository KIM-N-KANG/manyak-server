package com.knk.manyak.auth.repository

import com.knk.manyak.auth.entity.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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
}
