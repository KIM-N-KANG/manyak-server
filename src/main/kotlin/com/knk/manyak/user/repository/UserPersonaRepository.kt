package com.knk.manyak.user.repository

import com.knk.manyak.user.entity.UserPersona
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserPersonaRepository : JpaRepository<UserPersona, Long> {
    fun countByUserIdAndDeletedAtIsNull(userId: Long): Long
    fun findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId: Long): List<UserPersona>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserPersona p where p.publicId = :publicId and p.userId = :userId and p.deletedAt is null")
    fun findOwnedForUpdate(@Param("publicId") publicId: UUID, @Param("userId") userId: Long): UserPersona?
}
