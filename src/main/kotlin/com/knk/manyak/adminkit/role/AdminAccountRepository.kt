package com.knk.manyak.adminkit.role

import org.springframework.data.repository.Repository

/** 역할 변경은 운영 SQL로만 수행한다. */
interface AdminAccountRepository : Repository<AdminAccount, Long> {
    fun existsById(userId: Long): Boolean
}
