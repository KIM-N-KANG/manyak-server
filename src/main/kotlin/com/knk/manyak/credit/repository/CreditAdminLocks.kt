package com.knk.manyak.credit.repository

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.credit.service.CreditPolicyKey
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class LockedCreditTarget(val id: Long, val status: UserStatus)

interface CreditAdminLocks {
    fun policy(key: CreditPolicyKey)
    fun target(publicId: UUID): LockedCreditTarget?
}

/** 잠금은 변경·감사 트랜잭션이 끝날 때 해제된다. 테스트의 H2 대체 구현과 운영 SQL을 분리한다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class PostgresCreditAdminLocks(private val jdbc: JdbcTemplate) : CreditAdminLocks {
    override fun policy(key: CreditPolicyKey) {
        // MYCP 네임스페이스 + 저장 키의 안정적인 해시. 행이 아직 없어도 같은 키의 첫 생성까지 직렬화한다.
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", RowCallbackHandler { }, 0x4D594350, key.storageKey.hashCode())
    }

    override fun target(publicId: UUID): LockedCreditTarget? = jdbc.query(
        "SELECT id, status FROM users WHERE public_id = ? FOR NO KEY UPDATE",
        { rs, _ -> LockedCreditTarget(rs.getLong("id"), UserStatus.valueOf(rs.getString("status"))) }, publicId,
    ).singleOrNull()
    // NO KEY UPDATE는 탈퇴·정지의 사용자 UPDATE/비관적 쓰기 잠금과 충돌하지만 FK의 KEY SHARE와는 양립한다.
    // 따라서 이 잠금을 보유한 채 REQUIRES_NEW 첫 지갑 생성이 부모 FK를 확인해도 자기 자신을 기다리지 않는다.
    // 상태는 JPA 캐시를 거치지 않고 잠금 뒤 JDBC 결과에서 읽는다.
}
