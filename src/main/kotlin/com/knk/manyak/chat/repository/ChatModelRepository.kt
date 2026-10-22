package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.ChatModel
import org.springframework.data.jpa.repository.JpaRepository

interface ChatModelRepository : JpaRepository<ChatModel, String> {
    fun findByIsActiveTrueOrderBySortOrderAscCodeAsc(): List<ChatModel>
    fun findByIsDefaultTrueAndIsActiveTrue(): ChatModel?
}
