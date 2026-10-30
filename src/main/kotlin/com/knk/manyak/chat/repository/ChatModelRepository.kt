package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.ChatModel
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ChatModelRepository : JpaRepository<ChatModel, String> {
    fun findAllByOrderBySortOrderAscCodeAsc(): List<ChatModel>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ChatModel m where m.code = :code")
    fun findByCodeForUpdate(code: String): ChatModel?

    fun findByIsActiveTrueOrderBySortOrderAscCodeAsc(): List<ChatModel>
    fun findByIsDefaultTrueAndIsActiveTrue(): ChatModel?
}
