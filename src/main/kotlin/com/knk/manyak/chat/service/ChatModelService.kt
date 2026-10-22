package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.ChatModelResponse
import com.knk.manyak.chat.repository.ChatModelRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/** 캐시 없이 요청 시점의 모델과 가격을 읽고 불변 값으로 반환한다. */
@Service
@Transactional(readOnly = true)
class ChatModelService(private val repository: ChatModelRepository) {
    fun list(): List<ChatModelResponse> {
        val active = repository.findByIsActiveTrueOrderBySortOrderAscCodeAsc()
        check(active.count { it.isDefault } == 1) { "노출 중인 기본 채팅 모델이 없습니다." }
        return active.map { ChatModelResponse(it.code, it.name, it.turnCost, it.isDefault) }
    }

    fun defaultModel(): BillingModel = repository.findByIsDefaultTrueAndIsActiveTrue()
        ?.let { BillingModel(it.code, it.turnCost) } ?: error("노출 중인 기본 채팅 모델이 없습니다.")

    fun effective(code: String): BillingModel {
        val active = repository.findByIsActiveTrueOrderBySortOrderAscCodeAsc()
        val default = active.singleOrNull { it.isDefault } ?: error("노출 중인 기본 채팅 모델이 없습니다.")
        val selected = active.firstOrNull { it.code == code } ?: default
        return BillingModel(selected.code, selected.turnCost)
    }

    fun requireSelectable(code: String): String = repository.findById(code).orElse(null)
        ?.takeIf { it.isActive }?.code
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "선택할 수 없는 모델입니다.")

    fun litePrice(): Long = repository.findById("LITE").orElseThrow { IllegalStateException("LITE 모델이 없습니다.") }.turnCost
}

data class BillingModel(val code: String, val turnCost: Long)
