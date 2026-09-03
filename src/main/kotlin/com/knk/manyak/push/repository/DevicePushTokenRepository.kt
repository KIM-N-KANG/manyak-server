package com.knk.manyak.push.repository

import com.knk.manyak.push.entity.DevicePushToken
import org.springframework.data.jpa.repository.JpaRepository

interface DevicePushTokenRepository : JpaRepository<DevicePushToken, Long> {
    fun findByToken(token: String): DevicePushToken?

    /** 소유자 조건을 함께 건다 — 남의 토큰을 지우는 경로가 되면 안 된다. 지운 행 수를 돌려준다. */
    fun deleteByUserIdAndToken(userId: Long, token: String): Long

    /** 탈퇴 정리(UserWithdrawalService). 지운 행 수를 돌려준다. */
    fun deleteByUserId(userId: Long): Long
}
