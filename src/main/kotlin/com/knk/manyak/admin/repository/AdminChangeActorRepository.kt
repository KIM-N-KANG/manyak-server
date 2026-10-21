package com.knk.manyak.admin.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class AdminChangeActorRepository(private val jdbc: JdbcTemplate) {
    /** JDBC 스칼라 조회로 필터/호출 도메인의 영속성 컨텍스트에 남은 User 스냅샷을 재사용하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockActiveAdmin(userId: Long): Boolean = try {
        val status = jdbc.queryForList("SELECT status FROM users WHERE id = ? FOR UPDATE NOWAIT", String::class.java, userId)
            .singleOrNull()
        if (status != "ACTIVE") false else {
            // 역할 회수 SQL은 users를 잠그지 않을 수 있다. 역할 행도 커밋까지 잠가 검사 직후 DELETE를 막는다.
            jdbc.queryForList("SELECT user_id FROM admin_accounts WHERE user_id = ? FOR UPDATE NOWAIT", Long::class.java, userId)
                .isNotEmpty()
        }
    } catch (ex: UncategorizedSQLException) {
        // JDBC 기본 변환기는 PostgreSQL lock_not_available(55P03)을 분류하지 않을 수 있다.
        if (ex.sqlException?.sqlState == "55P03") throw CannotAcquireLockException("관리자 처리자 잠금 경합", ex)
        throw ex
    }
}
