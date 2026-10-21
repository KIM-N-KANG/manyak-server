package com.knk.manyak.admin.repository

import com.knk.manyak.admin.entity.AdminAuditLog
import org.springframework.data.repository.Repository

/** 추가만 허용하며 갱신과 삭제 연산은 제공하지 않는다. */
interface AdminAuditLogRepository : Repository<AdminAuditLog, Long> {
    fun save(log: AdminAuditLog): AdminAuditLog
}
