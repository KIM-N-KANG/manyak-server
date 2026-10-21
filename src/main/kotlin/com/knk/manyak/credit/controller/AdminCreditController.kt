package com.knk.manyak.credit.controller

import com.knk.manyak.admin.security.AdminRequester
import com.knk.manyak.credit.dto.*
import com.knk.manyak.credit.service.AdminCreditAdjustmentService
import com.knk.manyak.credit.service.AdminCreditPolicyService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/credits")
class AdminCreditController(
    private val adjustments: AdminCreditAdjustmentService,
    private val policies: AdminCreditPolicyService,
) {
    @PostMapping("/adjustments")
    fun adjust(@Valid @RequestBody body: AdminCreditAdjustmentRequest, request: HttpServletRequest) =
        adjustments.adjust(AdminRequester.userId(request), body)

    @GetMapping("/policies")
    fun policies() = policies.list()

    @PutMapping("/policies/{policyKey}")
    fun upsert(@PathVariable policyKey: String, @Valid @RequestBody body: AdminCreditPolicyRequest, request: HttpServletRequest) =
        policies.upsert(AdminRequester.userId(request), policyKey, body)

    @DeleteMapping("/policies/{policyKey}")
    fun delete(@PathVariable policyKey: String, @Valid @RequestBody body: AdminCreditPolicyDeleteRequest, request: HttpServletRequest) =
        policies.delete(AdminRequester.userId(request), policyKey, body.reason)
}
