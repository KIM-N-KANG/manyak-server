package com.knk.manyak.auth.repository

import com.knk.manyak.auth.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, Long> {
    // API 외부 식별자(public_id) 기준 조회. 순차 PK 열거(IDOR) 차단.
    fun findByPublicId(publicId: UUID): User?
}
