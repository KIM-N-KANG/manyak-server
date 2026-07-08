package com.knk.manyak.credit.service

import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.repository.CreditWalletRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 보상 적립 결과. [rewarded]가 false면 멱등 키 중복 또는 월 상한 초과로 이번 요청은 적립하지 않았다(잔액 불변). */
data class RewardOutcome(val rewarded: Boolean, val balance: Long)

/**
 * 사유별 월 상한(스펙 §4-3-7 초대 보상 월 한도). [cap]회 미만일 때만 적립하고, 집계 구간은 KST 월 등 호출부가 정한 [windowStart, windowEnd)다.
 * 판정은 지갑 행 락 안에서 수행되므로(같은 사용자 동시 적립이 직렬화됨) 경계에서의 초과 적립을 막는다.
 */
data class MonthlyRewardCap(val reason: CreditReason, val cap: Long, val windowStart: Instant, val windowEnd: Instant)

/**
 * 크레딧 지갑·원장의 기반 연산(스펙 §4-3-7 원장과 동시성). 적립/차감은 원장 행 추가와 지갑 balance 캐시 갱신을 한 트랜잭션에서 수행한다.
 *
 * - 적립([reward])은 멱등 키로 중복을 막는다(재시도·중복 클릭 안전).
 * - 차감([deduct])은 지갑 행 비관적 락으로 직렬화하고, 잔액이 부족하면 [InsufficientCreditException].
 *
 * 사유별(가입·초대·출석·소모) 정책과 API는 팬아웃 티켓(KNK-87·392·393·394·398·399)이 이 연산을 호출해 구성한다.
 */
@Service
class CreditWalletService(
    private val walletRepository: CreditWalletRepository,
    private val transactionRepository: CreditTransactionRepository,
    private val walletProvisioner: CreditWalletProvisioner,
) {

    /** 요청자의 현재 잔액. 지갑이 없으면 0. */
    @Transactional(readOnly = true)
    fun balanceOf(userId: Long): Long = walletRepository.findByUserId(userId)?.balance ?: 0

    /** 멱등 키로 이미 처리된 원장 행이 있는지 확인한다. 조회 전용이며 부수효과(적립)가 없다(스펙 §4-3-5 B17 attendedToday 판정용). */
    @Transactional(readOnly = true)
    fun hasTransaction(idempotencyKey: String): Boolean = transactionRepository.existsByIdempotencyKey(idempotencyKey)

    /**
     * 크레딧을 적립한다(양수). [idempotencyKey]가 이미 기록됐으면 적립하지 않고 `rewarded=false`를 반환한다(멱등).
     *
     * 동시 같은 키 요청은 원장 유니크 제약이 최종 방어한다(드문 경합은 오류로 드러나며, 재시도 시 키가 존재해 멱등).
     *
     * [monthlyCap]을 주면 지갑 행 락 안에서 해당 사유의 구간 집계가 상한 미만일 때만 적립한다(초대 보상 월 한도, 스펙 §4-3-7).
     * 카운트·판정·insert가 같은 락 구간에 있으므로, 같은 사용자 동시 적립이 경계에서 상한을 넘기지 못한다(락 밖 사전 카운트의 경합 제거).
     */
    @Transactional
    fun reward(
        userId: Long,
        amount: Long,
        reason: CreditReason,
        idempotencyKey: String,
        refType: String? = null,
        refId: Long? = null,
        monthlyCap: MonthlyRewardCap? = null,
    ): RewardOutcome {
        require(amount > 0) { "적립액은 양수여야 합니다: $amount" }
        // 빠른 경로: 이미 적립된 키면 락 없이 통과(완료 후 재시도의 흔한 경우).
        if (transactionRepository.existsByIdempotencyKey(idempotencyKey)) {
            return RewardOutcome(rewarded = false, balance = balanceOf(userId))
        }
        // 지갑을 먼저 보장(독립 트랜잭션)한 뒤 락을 잡는다. 첫 지갑 생성을 직렬화해, 동시 첫 적립에서 유효한 적립이 유실되지 않게 한다.
        ensureWallet(userId)
        val wallet = walletRepository.findByUserIdForUpdate(userId)
            ?: error("지갑을 보장한 뒤 조회에 실패했습니다: userId=$userId")
        // 락 획득 후 재확인: 같은 키 동시 요청이 락을 기다리는 사이 먼저 커밋했을 수 있다(중복 insert·유니크 위반 방지, 멱등 보장).
        if (transactionRepository.existsByIdempotencyKey(idempotencyKey)) {
            return RewardOutcome(rewarded = false, balance = wallet.balance)
        }
        // 월 상한 판정을 락 안에서 수행한다. 같은 사용자 동시 적립은 지갑 행 락으로 직렬화되므로, 여기서 세는 구간 집계가
        // 직전 적립까지 반영해 경계 초과를 막는다(스펙 §4-3-7 초대 보상 월 한도). 상한 이상이면 적립 없이 rewarded=false.
        if (monthlyCap != null) {
            val countInWindow = transactionRepository
                .countByUserIdAndReasonAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    userId,
                    monthlyCap.reason,
                    monthlyCap.windowStart,
                    monthlyCap.windowEnd,
                )
            if (countInWindow >= monthlyCap.cap) {
                return RewardOutcome(rewarded = false, balance = wallet.balance)
            }
        }
        transactionRepository.save(
            CreditTransaction(
                userId = userId,
                amount = amount,
                reason = reason,
                refType = refType,
                refId = refId,
                idempotencyKey = idempotencyKey,
            ),
        )
        wallet.balance += amount
        return RewardOutcome(rewarded = true, balance = wallet.balance)
    }

    /**
     * 요청자의 지갑이 없으면 만든다. 생성은 독립 트랜잭션([CreditWalletProvisioner.createWallet])이라,
     * 동시 첫 적립 경합으로 유니크 위반이 나도 그 실패는 내부 트랜잭션에 갇히고, 여기서 잡아 멱등하게 통과한다(지갑은 이미 존재).
     */
    private fun ensureWallet(userId: Long) {
        if (walletRepository.findByUserId(userId) != null) return
        try {
            walletProvisioner.createWallet(userId)
        } catch (ignored: DataIntegrityViolationException) {
            // 동시 첫 생성 경합: 다른 트랜잭션이 이미 지갑을 만들었다.
        }
    }

    /**
     * 크레딧을 차감한다(양수 [amount]만큼). 잔액이 부족하면 [InsufficientCreditException]을 던진다(호출부가 402로 변환).
     * 지갑 행을 비관적 락으로 잡아 동시 차감을 직렬화한다. 반환값은 차감 후 잔액.
     */
    @Transactional
    fun deduct(
        userId: Long,
        amount: Long,
        reason: CreditReason,
        refType: String? = null,
        refId: Long? = null,
    ): Long {
        require(amount > 0) { "차감액은 양수여야 합니다: $amount" }
        val wallet = walletRepository.findByUserIdForUpdate(userId)
            ?: throw InsufficientCreditException(userId, required = amount, balance = 0)
        if (wallet.balance < amount) {
            throw InsufficientCreditException(userId, required = amount, balance = wallet.balance)
        }
        transactionRepository.save(
            CreditTransaction(
                userId = userId,
                amount = -amount,
                reason = reason,
                refType = refType,
                refId = refId,
            ),
        )
        wallet.balance -= amount
        return wallet.balance
    }

    /**
     * 대사 배치가 유실 환불을 사후 보정한다(스펙 §4-3-7, KNK-448). 한 그룹(userId, refType, refId)에 있어야 할
     * 총 환불 수([targetRefundCount] = charge 수 − 완료 수)에 맞춰, 아직 모자란 만큼만 REFUND 행을 추가한다.
     *
     * 멱등·동시성: 지갑 행을 비관적 락으로 잡은 뒤 **현재 REFUND 수를 다시 세어** 부족분(`target − 현재`)만 발행한다.
     * 재실행·동시 실행이 겹쳐도 락 안 재확인이 초과 환불을 막는다. 이미 충분하면(부족분 ≤ 0) 아무것도 하지 않는다.
     * in-flight 환불이 [reward]로 유니크 키를 쓰는 것과 달리, 이 사후 환불의 멱등성은 이 재확인이 보장한다(키 없음).
     *
     * @return 이번에 추가한 REFUND 행 수(0이면 이미 충분).
     */
    @Transactional
    fun reconcileRefunds(
        userId: Long,
        refType: String,
        refId: Long,
        unitAmount: Long,
        targetRefundCount: Long,
    ): Int {
        require(unitAmount > 0) { "환불 단위액은 양수여야 합니다: $unitAmount" }
        if (targetRefundCount <= 0) return 0
        // 지갑이 없으면 차감된 적이 없다는 뜻이라 대사할 것도 없다.
        val wallet = walletRepository.findByUserIdForUpdate(userId) ?: return 0
        val currentRefunds = transactionRepository
            .countByUserIdAndRefTypeAndRefIdAndReason(userId, refType, refId, CreditReason.REFUND)
        val missing = targetRefundCount - currentRefunds
        if (missing <= 0) return 0
        repeat(missing.toInt()) {
            transactionRepository.save(
                CreditTransaction(
                    userId = userId,
                    amount = unitAmount,
                    reason = CreditReason.REFUND,
                    refType = refType,
                    refId = refId,
                ),
            )
            wallet.balance += unitAmount
        }
        return missing.toInt()
    }
}
