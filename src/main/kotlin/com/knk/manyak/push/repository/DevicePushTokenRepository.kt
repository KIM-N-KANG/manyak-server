package com.knk.manyak.push.repository

import com.knk.manyak.push.entity.DevicePushToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DevicePushTokenRepository : JpaRepository<DevicePushToken, Long> {
    fun findByToken(token: String): DevicePushToken?

    /**
     * 소유자 조건을 함께 건 **단일 조건부 DELETE**다. 파생 삭제(`deleteByUserIdAndToken`)는 조회 후 id로 지워,
     * 조회와 삭제 사이에 소유자가 바뀐 행(한 기기에서 계정 전환)까지 지운다 — 남의 토큰을 지우는 경로가 되면
     * 안 되므로 소유자 판정을 DELETE 문 안에 둔다(`SocialAccountRepository.touchLastLoginAt` 관례).
     * 지운 행 수를 돌려준다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DevicePushToken t WHERE t.userId = :userId AND t.token = :token")
    fun deleteByUserIdAndToken(@Param("userId") userId: Long, @Param("token") token: String): Int

    /** 탈퇴 정리(UserWithdrawalService). 같은 이유로 조건부 DELETE다. 지운 행 수를 돌려준다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DevicePushToken t WHERE t.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: Long): Int
}
