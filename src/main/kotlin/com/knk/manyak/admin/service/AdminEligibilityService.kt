package com.knk.manyak.admin.service

import com.knk.manyak.adminkit.role.AdminRoleReader
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import org.springframework.stereotype.Service

@Service
class AdminEligibilityService(private val adminRoles: AdminRoleReader) {
    fun rejectionReason(user: User?): String? = when {
        user == null -> "no_member"
        user.status == UserStatus.DELETED -> "deleted"
        user.status == UserStatus.SUSPENDED -> "suspended"
        !adminRoles.hasRole(user.id) -> "no_role"
        else -> null
    }
}
