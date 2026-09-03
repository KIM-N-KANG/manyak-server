package com.knk.manyak.push.repository

import com.knk.manyak.push.entity.DevicePushToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DevicePushTokenRepository : JpaRepository<DevicePushToken, Long> {
    /**
     * 등록(upsert)이 잡을 토큰 행. **삭제 중인 행을 읽지 않도록 토큰 행도 잠근다**(Codex 3차 리뷰 P2) —
     * 잠금 없이 읽으면 다른 회원의 커밋 전 `unregister`가 지운 행을 그대로 읽고, 그 DELETE가 커밋된 뒤
     * dirty checking UPDATE가 0건이 되어 stale-state 500으로 토큰이 사라진다.
     * 잠금 순서는 users(요청자 행) → device_push_tokens 한 방향 그대로다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM DevicePushToken t WHERE t.token = :token")
    fun findByTokenForUpdate(@Param("token") token: String): DevicePushToken?

    /** 회원당 기기 상한(DevicePushTokenService.MAX_DEVICES_PER_USER) 집행용. 가장 안 쓴 기기가 앞에 온다. */
    fun findAllByUserIdOrderByUpdatedAtAsc(userId: Long): List<DevicePushToken>

    /** 발송 경로(FcmPushSender): 회원의 등록 기기 전부. */
    fun findAllByUserId(userId: Long): List<DevicePushToken>

    /**
     * 소유자 조건을 함께 건 **단일 조건부 DELETE**다. 파생 삭제(`deleteByUserIdAndToken`)는 조회 후 id로 지워,
     * 조회와 삭제 사이에 소유자가 바뀐 행(한 기기에서 계정 전환)까지 지운다 — 남의 토큰을 지우는 경로가 되면
     * 안 되므로 소유자 판정을 DELETE 문 안에 둔다(`SocialAccountRepository.touchLastLoginAt` 관례).
     * 지운 행 수를 돌려준다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DevicePushToken t WHERE t.userId = :userId AND t.token = :token")
    fun deleteByUserIdAndToken(@Param("userId") userId: Long, @Param("token") token: String): Int

    /**
     * 기기 상한 축출(DevicePushTokenService). `deleteAll(entities)`는 id만으로 지워, 후보를 뽑은 뒤 다른 회원이
     * 그 행의 소유권을 가져가 커밋하면 남의 토큰을 지운다(Codex 4차 리뷰 P2). 삭제 시점에 소유자를 다시
     * 평가하도록 조건을 DELETE 문 안에 둔다. 지운 행 수를 돌려준다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DevicePushToken t WHERE t.userId = :userId AND t.id IN :ids")
    fun deleteByUserIdAndIdIn(@Param("userId") userId: Long, @Param("ids") ids: Collection<Long>): Int

    /** 탈퇴 정리(UserWithdrawalService). 같은 이유로 조건부 DELETE다. 지운 행 수를 돌려준다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DevicePushToken t WHERE t.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: Long): Int
}
