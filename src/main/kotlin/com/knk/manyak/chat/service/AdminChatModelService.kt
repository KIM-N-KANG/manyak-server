package com.knk.manyak.chat.service

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.chat.dto.*
import com.knk.manyak.chat.entity.ChatModel
import com.knk.manyak.chat.repository.ChatModelRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant

@Service
class AdminChatModelService(
    private val models: ChatModelRepository,
    private val audit: AdminAuditService,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(): AdminChatModelsResponse {
        val rows = models.findAllByOrderBySortOrderAscCodeAsc()
        val now = clock.instant()
        return AdminChatModelsResponse(rows.map { response(it, now) })
    }

    @Transactional
    fun upsert(adminUserId: Long, code: String, request: AdminChatModelPromotionRequest): AdminChatModelResponse {
        if (request.turnCost !in 0..10000) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "프로모션 가격은 0 이상 10000 이하여야 합니다.")
        if (!request.endsAt.isAfter(request.startsAt)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "종료 시각은 시작 시각 이후여야 합니다.")
        val row = locked(code)
        if (!request.endsAt.isAfter(clock.instant())) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "종료 시각은 현재 이후여야 합니다.")
        val before = state(row)
        row.promoTurnCost = request.turnCost
        row.promoStartsAt = request.startsAt
        row.promoEndsAt = request.endsAt
        audit.recordChange(adminUserId, AdminAuditAction.CHAT_MODEL_PROMOTION_UPSERTED, code, request.reason, before, state(row))
        return response(row, clock.instant())
    }

    @Transactional
    fun delete(adminUserId: Long, code: String, reason: String): AdminChatModelResponse {
        val row = locked(code)
        val before = state(row) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "프로모션이 없습니다.")
        row.promoTurnCost = null
        row.promoStartsAt = null
        row.promoEndsAt = null
        audit.recordChange(adminUserId, AdminAuditAction.CHAT_MODEL_PROMOTION_DELETED, code, reason, before, null)
        return response(row, clock.instant())
    }

    private fun locked(code: String) = models.findByCodeForUpdate(code)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "모델을 찾을 수 없습니다.")

    private fun state(row: ChatModel) = row.promoTurnCost?.let {
        mapper.createObjectNode().put("turnCost", it).put("startsAt", row.promoStartsAt.toString()).put("endsAt", row.promoEndsAt.toString())
    }

    private fun response(row: ChatModel, now: Instant) = AdminChatModelResponse(
        row.code, row.name, row.turnCost, row.isActive, row.isDefault,
        row.promoTurnCost?.let { cost -> AdminChatModelPromotionResponse(cost, row.promoStartsAt!!, row.promoEndsAt!!,
            when {
                now.isBefore(row.promoStartsAt) -> ChatModelPromotionStatus.SCHEDULED
                now.isBefore(row.promoEndsAt) -> ChatModelPromotionStatus.ACTIVE
                else -> ChatModelPromotionStatus.ENDED
            }) },
    )
}
