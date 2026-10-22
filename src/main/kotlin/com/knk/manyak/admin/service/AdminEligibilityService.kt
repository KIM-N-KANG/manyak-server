package com.knk.manyak.admin.service

import com.knk.manyak.admin.repository.AdminAccountRepository
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import org.springframework.stereotype.Service

@Service
class AdminEligibilityService(private val accounts: AdminAccountRepository) {
    fun rejectionReason(user: User?): String? = when {
        user == null -> "no_member"
        user.status == UserStatus.DELETED -> "deleted"
        user.status == UserStatus.SUSPENDED -> "suspended"
        !accounts.existsById(user.id) -> "no_role"
        else -> null
    }
}
