package com.knk.manyak.auth.repository

import com.knk.manyak.adminkit.audit.AdminActorAccountLock
import com.knk.manyak.adminkit.audit.translateNowaitFailure
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** adminkit 변경 감사의 처리자 회원 행 잠금(KNK-2056). JDBC 스칼라 조회라 영속성 컨텍스트의 회원 스냅샷을 쓰지 않는다. */
@Repository
class AdminActorAccountLockJdbc(private val jdbc: JdbcTemplate) : AdminActorAccountLock {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockActive(userId: Long): Boolean = translateNowaitFailure {
        jdbc.queryForList("SELECT status FROM users WHERE id = ? FOR UPDATE NOWAIT", String::class.java, userId).singleOrNull()
    } == "ACTIVE"
}
