package com.knk.manyak.credit.repository

import com.knk.manyak.credit.service.CreditPolicyKey
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

interface CreditAdminLocks {
    fun policy(key: CreditPolicyKey)
}

/** 잠금은 변경·감사 트랜잭션이 끝날 때 해제된다. 테스트의 H2 대체 구현과 운영 SQL을 분리한다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class PostgresCreditAdminLocks(private val jdbc: JdbcTemplate) : CreditAdminLocks {
    override fun policy(key: CreditPolicyKey) {
        // MYCP 네임스페이스 + 저장 키의 안정적인 해시. 행이 아직 없어도 같은 키의 첫 생성까지 직렬화한다.
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", RowCallbackHandler { }, 0x4D594350, key.storageKey.hashCode())
    }
}
