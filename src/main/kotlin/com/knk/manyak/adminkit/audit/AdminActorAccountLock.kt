package com.knk.manyak.adminkit.audit

import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.UncategorizedSQLException

/**
 * 변경 감사의 처리자 회원 행을 기다리지 않고 잠그는 입구(KNK-2056). 회원 테이블은 auth 소유라 adminkit이 정의하고 auth가 구현한다
 * (BE-061: 아래 계층이 값을 돌려받아야 하면 아래 계층이 인터페이스를 정의한다).
 */
fun interface AdminActorAccountLock {

    /**
     * 처리자 회원 행을 `FOR UPDATE NOWAIT`로 잠그고 상태가 ACTIVE인지 돌려준다. 회원이 없으면 false다.
     * 잠금 경합은 [translateNowaitFailure]처럼 [CannotAcquireLockException]으로 던진다.
     */
    fun lockActive(userId: Long): Boolean
}

/** JDBC 기본 변환기는 PostgreSQL lock_not_available(55P03)을 분류하지 않을 수 있어 잠금 경합 예외로 바꾼다. */
fun <T> translateNowaitFailure(block: () -> T): T = try {
    block()
} catch (ex: UncategorizedSQLException) {
    if (ex.sqlException?.sqlState == "55P03") throw CannotAcquireLockException("관리자 처리자 잠금 경합", ex)
    throw ex
}
