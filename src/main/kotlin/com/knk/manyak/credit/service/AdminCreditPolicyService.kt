package com.knk.manyak.credit.service

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.credit.dto.*
import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Service
class AdminCreditPolicyService(
    private val repository: CreditPolicyRepository,
    private val policies: CreditPolicyService,
    private val audit: AdminAuditService,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(): AdminCreditPoliciesResponse {
        val rows = repository.findAll().associateBy { it.policyKey }
        val effective = policies.effectiveAmounts()
        return AdminCreditPoliciesResponse(CreditPolicyKey.entries.sortedBy { it.storageKey }.map {
            response(it, rows[it.storageKey], effective.getValue(it))
        })
    }

    @Transactional
    fun upsert(adminUserId: Long, policyKey: String, request: AdminCreditPolicyRequest): AdminCreditPolicyResponse {
        val key = keyOf(policyKey)
        if (request.amount !in key.minimumAmount..10000) throw ResponseStatusException(HttpStatus.BAD_REQUEST,
            "$policyKey 는 ${key.minimumAmount} 이상 10000 이하여야 합니다.")
        val now = clock.instant()
        if (request.effectiveUntil?.isAfter(now) == false) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "종료 시각은 현재 이후여야 합니다.")
        val before = state(repository.findById(policyKey).orElse(null))
        val row = repository.save(CreditPolicy(policyKey, request.amount, request.effectiveUntil, now))
        audit.recordChange(adminUserId, AdminAuditAction.CREDIT_POLICY_UPSERTED, policyKey, request.reason, before, state(row))
        return response(key, row, policies.effectiveAmounts().getValue(key))
    }

    @Transactional
    fun delete(adminUserId: Long, policyKey: String, reason: String): AdminCreditPolicyResponse {
        val key = keyOf(policyKey)
        val before = repository.findById(policyKey).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "오버라이드가 없습니다.") }
        repository.delete(before)
        audit.recordChange(adminUserId, AdminAuditAction.CREDIT_POLICY_DELETED, policyKey, reason, state(before), null)
        return response(key, null, policies.effectiveAmounts().getValue(key))
    }

    private fun keyOf(value: String) = CreditPolicyKey.entries.firstOrNull { it.storageKey == value }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "정책 키를 찾을 수 없습니다.")

    private fun response(key: CreditPolicyKey, row: CreditPolicy?, effective: Long) = AdminCreditPolicyResponse(
        key.storageKey, policies.defaultAmountOf(key), effective, key.minimumAmount,
        row?.let { AdminCreditPolicyOverrideResponse(it.amount, it.effectiveUntil, it.updatedAt) },
    )

    private fun state(row: CreditPolicy?): JsonNode? = row?.let {
        mapper.createObjectNode().put("policyKey", it.policyKey).put("amount", it.amount).apply {
            if (it.effectiveUntil == null) putNull("effectiveUntil") else put("effectiveUntil", it.effectiveUntil.toString())
        }
    }
}
