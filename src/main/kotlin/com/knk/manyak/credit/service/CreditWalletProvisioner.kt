package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditWallet
import com.knk.manyak.credit.repository.CreditWalletRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 크레딧 지갑 생성(영속성)만 담당한다. [CreditWalletService]와 분리된 별도 빈으로 두어 트랜잭션 프록시 경계를 확보한다.
 *
 * [createWallet]은 독립 트랜잭션([Propagation.REQUIRES_NEW])이라, 동시 첫 적립으로 `user_id` 유니크 위반이 나면
 * **이 내부 트랜잭션만 rollback**되고 바깥(적립) 트랜잭션은 재조회로 이어갈 수 있다(로그인 GoogleAccountRegistrar 선례).
 * 이렇게 격리하지 않으면 경합 시 유효한 적립이 통째로 유실된다.
 */
@Component
class CreditWalletProvisioner(
    private val walletRepository: CreditWalletRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createWallet(userId: Long): CreditWallet =
        walletRepository.saveAndFlush(CreditWallet(userId = userId))
}
