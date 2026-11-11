package com.knk.manyak.auth.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 다른 모듈이 소유한 계정 컬럼(초대, 이관, 푸시 동의)을 쓰는 명령(KNK-2056).
 *
 * 호출자가 같은 트랜잭션에서 [AccountLockService.lock]으로 회원 행을 먼저 잠근다. 명령은 그 잠근 인스턴스를 영속성 컨텍스트에서
 * 꺼내 필드만 바꾸므로 SQL을 내지 않고, UPDATE는 전처럼 flush 때 더티 체킹으로 나간다. 이 트랜잭션에서 쓰기 잠금으로 잡히지 않은
 * 회원이면 쓰지 않고 [IllegalStateException]을 던진다(잠금 없는 갱신이 동시 요청 사이에 사라지는 것을 막는다).
 */
@Service
class AccountCommandService(
    private val users: UserRepository,
    private val entityManager: EntityManager,
) {

    /** 초대 코드를 처음 발급해 기록한다(스펙 4-3-7). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun assignInviteCode(userId: Long, inviteCode: String) {
        writeLocked(userId).inviteCode = inviteCode
    }

    /** 초대 코드를 입력한 회원의 초대자를 기록한다. 평생 1회 자격 소진 표식이다(KNK-567). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun recordInviter(userId: Long, inviterUserId: Long) {
        writeLocked(userId).inviterUserId = inviterUserId
    }

    /** 이관 시도 수를 하나 늘린다(스펙 4-3-5 B19). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun recordMigrationAttempt(userId: Long) {
        writeLocked(userId).migrationAttempts += 1
    }

    /** 이관 완료 시각을 기록해 이관을 닫는다(스펙 4-3-5). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun markMigrated(userId: Long, at: Instant) {
        writeLocked(userId).migratedAt = at
    }

    /** 푸시 수신 동의 세 컬럼을 그대로 바꾼다. 값의 규칙(최초 동의 시각 유지, 철회 시 함께 지움)은 NOTIFICATION이 정한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun updatePushConsent(userId: Long, servicePushEnabled: Boolean, marketingPushAgreedAt: Instant?, marketingPushNightAgreedAt: Instant?) {
        val user = writeLocked(userId)
        user.servicePushEnabled = servicePushEnabled
        user.marketingPushAgreedAt = marketingPushAgreedAt
        user.marketingPushNightAgreedAt = marketingPushNightAgreedAt
    }

    /** 이 트랜잭션에서 쓰기 잠금으로 잡은 회원. 잠근 회원은 영속성 컨텍스트에 있어 조회 SQL이 나가지 않는다. */
    private fun writeLocked(userId: Long): User {
        val user = users.findById(userId).orElseThrow()
        check(entityManager.getLockMode(user) == LockModeType.PESSIMISTIC_WRITE) {
            "회원 행을 이 트랜잭션에서 먼저 잠가야 합니다(AccountLockService.lock): userId=$userId"
        }
        return user
    }
}
