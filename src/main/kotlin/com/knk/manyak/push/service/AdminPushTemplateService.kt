package com.knk.manyak.push.service

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.push.dto.*
import com.knk.manyak.push.entity.PushMessageTemplate
import com.knk.manyak.push.repository.PushMessageTemplateRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant

@Service
class AdminPushTemplateService(
    private val repository: PushMessageTemplateRepository,
    private val text: PushMessageTemplateService,
    private val audit: AdminAuditService,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun get(templateKey: String): AdminPushTemplateResponse = response(keyOf(templateKey), clock.instant())

    @Transactional
    fun upsert(adminUserId: Long, templateKey: String, request: AdminPushTemplateRequest): AdminPushTemplateResponse {
        val key = keyOf(templateKey)
        val now = clock.instant()
        val from = request.effectiveFrom ?: now
        if (from.isBefore(now) || request.effectiveUntil?.isAfter(from) == false)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "시작은 현재 이후, 종료는 시작 이후여야 합니다.")
        val row = repository.saveAndFlush(PushMessageTemplate(templateKey = templateKey, title = request.title,
            body = request.body, effectiveFrom = from, effectiveUntil = request.effectiveUntil, createdAt = now))
        audit.recordChange(adminUserId, AdminAuditAction.PUSH_TEMPLATE_UPSERTED, templateKey, request.reason,
            after = mapper.valueToTree(override(row, now)))
        return response(key, now)
    }

    @Transactional
    fun reset(adminUserId: Long, templateKey: String, reason: String): AdminPushTemplateResponse {
        val key = keyOf(templateKey)
        val now = clock.instant()
        val rows = repository.findByTemplateKeyOrderByEffectiveFromDescIdDesc(templateKey)
            .filter { active(it, now) || (it.effectiveFrom.isAfter(now) &&
                (it.effectiveUntil == null || it.effectiveUntil!!.isAfter(it.effectiveFrom))) }
        val before = mapper.valueToTree<tools.jackson.databind.JsonNode>(rows.map { override(it, now) })
        rows.forEach { row -> row.effectiveUntil = if (row.effectiveFrom.isAfter(now)) row.effectiveFrom else now }
        repository.flush()
        audit.recordChange(adminUserId, AdminAuditAction.PUSH_TEMPLATE_RESET, templateKey, reason, before = before)
        return response(key, now)
    }

    private fun response(key: PushTemplateKey, now: Instant): AdminPushTemplateResponse {
        val rows = repository.findByTemplateKeyOrderByEffectiveFromDescIdDesc(key.storageKey)
        val selected = rows.firstOrNull { active(it, now) }
        val effective = selected?.let { PushMessageText(it.title, it.body) } ?: text.defaultTextOf(key)
        return AdminPushTemplateResponse(key.storageKey,
            AdminPushEffectiveTextResponse(effective.title, effective.body, selected == null), rows.map { override(it, now) })
    }
    private fun active(row: PushMessageTemplate, now: Instant) = !now.isBefore(row.effectiveFrom) &&
        (row.effectiveUntil == null || now.isBefore(row.effectiveUntil))
    private fun override(row: PushMessageTemplate, now: Instant) = AdminPushTemplateOverrideResponse(
        row.title, row.body, row.effectiveFrom, row.effectiveUntil, active(row, now))
    private fun keyOf(value: String) = PushTemplateKey.entries.firstOrNull { it.storageKey == value }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "문구 템플릿이 없습니다.")
}
