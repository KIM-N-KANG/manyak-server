package com.knk.manyak.admin

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.security.AdminRequester
import com.knk.manyak.admin.service.AdminAuditService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper

/** S1에는 보호 대상 조회 API가 없어 테스트 전용 핸들러로 체인과 다음 작업의 호출 계약을 검증한다. */
@TestConfiguration
class AdminProbeConfig {
    @Bean fun adminProbe(audits: AdminAuditService, jdbc: JdbcTemplate) = AdminProbe(audits, jdbc)
}

@RestController
@RequestMapping("/api/v1/admin/foundation-probe")
class AdminProbe(private val audits: AdminAuditService, private val jdbc: JdbcTemplate) {
    @GetMapping
    fun get(request: HttpServletRequest) = mapOf("adminUserId" to AdminRequester.userId(request))

    @PutMapping
    @Transactional
    fun change(request: HttpServletRequest): Map<String, String> {
        val id = AdminRequester.userId(request)
        jdbc.update("UPDATE users SET nickname = 'changed-by-admin' WHERE id = ?", id)
        val mapper = ObjectMapper()
        val row = audits.recordChange(id, AdminAuditAction.MEMBER_SUSPENDED, "00000000-0000-4000-8000-000000000001",
            "테스트 사유", mapper.readTree("{\"status\":\"ACTIVE\"}"), mapper.readTree("{\"status\":\"SUSPENDED\"}"))
        return mapOf("requestId" to row.requestId)
    }

    @PostMapping("/access")
    fun access(request: HttpServletRequest): Map<String, String> {
        val id = AdminRequester.userId(request)
        audits.recordAccess(id, AdminAuditAction.MEMBER_SEARCHED, "검색어")
        audits.recordAccess(id, AdminAuditAction.MEMBER_VIEWED, "00000000-0000-4000-8000-000000000001")
        return mapOf("result" to "private")
    }
}
