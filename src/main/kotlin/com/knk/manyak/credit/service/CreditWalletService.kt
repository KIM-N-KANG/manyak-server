package com.knk.manyak.credit.service

import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.repository.CreditWalletRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 보상 적립 결과. [rewarded]가 false면 멱등 키 중복으로 이번 요청은 적립하지 않았다(잔액 불변). */
data class RewardOutcome(val rewarded: Boolean, val balance: Long)

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

    /**
     * 크레딧을 적립한다(양수). [idempotencyKey]가 이미 기록됐으면 적립하지 않고 `rewarded=false`를 반환한다(멱등).
     *
     * 동시 같은 키 요청은 원장 유니크 제약이 최종 방어한다(드문 경합은 오류로 드러나며, 재시도 시 키가 존재해 멱등).
     */
    @Transactional
    fun reward(
        userId: Long,
        amount: Long,
        reason: CreditReason,
        idempotencyKey: String,
        refType: String? = null,
        refId: Long? = null,
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
}
