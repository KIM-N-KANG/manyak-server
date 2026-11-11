package com.knk.manyak.adminkit.audit

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class AdminChangeActorRepository(
    private val jdbc: JdbcTemplate,
    private val accounts: AdminActorAccountLock,
) {
    /**
     * 처리자 회원 행, 관리자 역할 행 순서로 NOWAIT 잠금을 잡고 둘 다 유효한지 돌려준다. 회원 행 잠금은 auth가 JDBC 스칼라 조회로 해
     * 필터나 호출 도메인의 영속성 컨텍스트에 남은 회원 스냅샷을 재사용하지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockActiveAdmin(userId: Long): Boolean =
        accounts.lockActive(userId) && translateNowaitFailure {
            // 역할 회수 SQL은 users를 잠그지 않을 수 있다. 역할 행도 커밋까지 잠가 검사 직후 DELETE를 막는다.
            jdbc.queryForList("SELECT user_id FROM admin_accounts WHERE user_id = ? FOR UPDATE NOWAIT", Long::class.java, userId)
                .isNotEmpty()
        }
}
