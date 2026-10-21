package com.knk.manyak.credit.support

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.credit.repository.CreditAdminLocks
import com.knk.manyak.credit.repository.LockedCreditTarget
import com.knk.manyak.credit.service.CreditPolicyKey
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** H2에는 PostgreSQL advisory/NO KEY UPDATE 잠금이 없다. 운영 SQL은 별도 실제 PG 테스트로 검증한다. */
@TestConfiguration
class H2CreditAdminLocksConfig {
    @Bean @Primary
    fun h2CreditAdminLocks(jdbc: JdbcTemplate): CreditAdminLocks = H2CreditAdminLocks(jdbc)
}

@Transactional(propagation = Propagation.MANDATORY)
class H2CreditAdminLocks(private val jdbc: JdbcTemplate) : CreditAdminLocks {
    private val policies = ConcurrentHashMap<CreditPolicyKey, ReentrantLock>()
    override fun policy(key: CreditPolicyKey) {
        val lock = policies.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) { lock.unlock() }
        })
    }
    override fun target(publicId: UUID): LockedCreditTarget? = jdbc.query(
        "SELECT id, status FROM users WHERE public_id = ? FOR UPDATE",
        { rs, _ -> LockedCreditTarget(rs.getLong("id"), UserStatus.valueOf(rs.getString("status"))) }, publicId,
    ).singleOrNull()
}
