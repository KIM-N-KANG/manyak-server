package com.knk.manyak.adminkit.role

import org.springframework.stereotype.Service

/** 다른 모듈이 관리자 역할 보유 여부를 묻는 입구. 잠금 없이 호출자 트랜잭션에서 조회한다. */
@Service
class AdminRoleReader(private val accounts: AdminAccountRepository) {
    fun hasRole(userId: Long): Boolean = accounts.existsById(userId)
}
