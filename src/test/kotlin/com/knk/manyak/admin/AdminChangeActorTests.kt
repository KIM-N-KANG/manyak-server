package com.knk.manyak.admin

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.repository.AdminAuditLogRepository
import com.knk.manyak.admin.repository.AdminChangeActorRepository
import com.knk.manyak.admin.service.AdminAuditService
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.server.ResponseStatusException

class AdminChangeActorTests {
    @Test fun `처리자 잠금 경합도 자격 거절과 같은 403이고 감사 저장을 호출하지 않는다`() {
        val actors = mock(AdminChangeActorRepository::class.java)
        val logs = mock(AdminAuditLogRepository::class.java)
        val audits = AdminAuditService(logs, mock(PlatformTransactionManager::class.java), actors, mock(EntityManager::class.java))
        `when`(actors.lockActiveAdmin(1)).thenThrow(CannotAcquireLockException("busy"))
        assertThatThrownBy { audits.recordChange(1, AdminAuditAction.CREDIT_GRANTED, "target", "사유") }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) {
                assertThat(it.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
                assertThat(it.reason).isEqualTo("관리자 권한이 없습니다.")
            }
        verifyNoInteractions(logs)
    }
    @Test fun `재검증의 DB 오류도 기존 감사 저장과 같은 안전한 500으로 처리한다`() {
        val actors = mock(AdminChangeActorRepository::class.java)
        val logs = mock(AdminAuditLogRepository::class.java)
        val audits = AdminAuditService(logs, mock(PlatformTransactionManager::class.java), actors, mock(EntityManager::class.java))
        `when`(actors.lockActiveAdmin(1)).thenThrow(org.springframework.dao.DataIntegrityViolationException("sensitive database detail"))
        assertThatThrownBy { audits.recordChange(1, AdminAuditAction.CREDIT_GRANTED, "target", "사유") }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) {
                assertThat(it.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                assertThat(it.reason).isEqualTo("서버 오류가 발생했습니다.")
                assertThat(it.cause).isNull()
            }
        verifyNoInteractions(logs)
    }

}
