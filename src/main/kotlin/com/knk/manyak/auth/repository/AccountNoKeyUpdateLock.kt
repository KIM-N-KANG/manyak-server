package com.knk.manyak.auth.repository

import com.knk.manyak.auth.dto.UserStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** [AccountNoKeyUpdateLock.lock]의 결과. 잠금 뒤 JDBC 결과에서 읽은 내부 id와 상태다. */
data class NoKeyLockedAccount(val id: Long, val status: UserStatus)

/** 회원 행 `FOR NO KEY UPDATE` 잠금(KNK-2056에서 credit의 `CreditAdminLocks.target`을 옮김). 테스트 H2는 대체 구현을 쓴다. */
fun interface AccountNoKeyUpdateLock {
    fun lock(publicId: UUID): NoKeyLockedAccount?
}

/** 잠금은 호출자 트랜잭션이 끝날 때 해제된다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class PostgresAccountNoKeyUpdateLock(private val jdbc: JdbcTemplate) : AccountNoKeyUpdateLock {
    override fun lock(publicId: UUID): NoKeyLockedAccount? = jdbc.query(
        "SELECT id, status FROM users WHERE public_id = ? FOR NO KEY UPDATE",
        { rs, _ -> NoKeyLockedAccount(rs.getLong("id"), UserStatus.valueOf(rs.getString("status"))) }, publicId,
    ).singleOrNull()
    // NO KEY UPDATE는 탈퇴·정지의 사용자 UPDATE/비관적 쓰기 잠금과 충돌하지만 FK의 KEY SHARE와는 양립한다.
    // 따라서 이 잠금을 보유한 채 REQUIRES_NEW 첫 지갑 생성이 부모 FK를 확인해도 자기 자신을 기다리지 않는다.
    // 상태는 JPA 캐시를 거치지 않고 잠금 뒤 JDBC 결과에서 읽는다.
}
