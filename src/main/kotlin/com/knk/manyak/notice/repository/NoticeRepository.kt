package com.knk.manyak.notice.repository

import com.knk.manyak.notice.entity.Notice
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface NoticeRepository : JpaRepository<Notice, Long> {
    fun findByPublicId(publicId: UUID): Notice?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notice n where n.publicId=:publicId")
    fun lockByPublicId(publicId: UUID): Notice?
}
