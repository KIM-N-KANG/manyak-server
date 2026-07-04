package com.knk.manyak.credit.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.repository.CreditWalletRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles

/**
 * CreditWalletService의 기반 연산 통합 검증(스펙 §4-3-7 원장과 동시성).
 *
 * - reward: 지갑 생성·적립, 멱등 키 중복 시 미적립(rewarded=false), 잔액 = 원장 합계.
 * - deduct: 잔액 차감·음수 원장 행, 잔액 부족/지갑 부재 시 InsufficientCreditException.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreditWalletServiceIntegrationTest {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var service: CreditWalletService
    @Autowired private lateinit var walletRepository: CreditWalletRepository
    @Autowired private lateinit var transactionRepository: CreditTransactionRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private var userId: Long = 0

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        userId = userRepository.save(User(nickname = "크레딧유저", status = UserStatus.ACTIVE)).id
    }

    @Test
    fun `reward는 지갑을 만들고 잔액을 적립한다`() {
        val outcome = service.reward(userId, 100, CreditReason.SIGNUP_REWARD, "signup:$userId")

        assertThat(outcome.rewarded).isTrue()
        assertThat(outcome.balance).isEqualTo(100)
        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(100)
        assertThat(transactionRepository.findAll()).hasSize(1)
    }

    @Test
    fun `같은 멱등 키로 재적립하면 rewarded=false고 잔액이 그대로다`() {
        service.reward(userId, 100, CreditReason.SIGNUP_REWARD, "signup:$userId")

        val second = service.reward(userId, 100, CreditReason.SIGNUP_REWARD, "signup:$userId")

        assertThat(second.rewarded).isFalse()
        assertThat(second.balance).isEqualTo(100)
        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(100)
        assertThat(transactionRepository.findAll()).hasSize(1)
    }

    @Test
    fun `다른 키로는 각각 적립되고 지갑은 한 번만 생성된다`() {
        // 새 유저에게 서로 다른 키로 연속 적립: 첫 적립이 지갑을 만들고 둘째는 그 지갑에 더한다(create-once).
        service.reward(userId, 100, CreditReason.SIGNUP_REWARD, "signup:$userId")
        service.reward(userId, 50, CreditReason.ATTENDANCE_REWARD, "attendance:$userId:2026-07-05")

        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(150)
        assertThat(walletRepository.count()).isEqualTo(1)
        assertThat(transactionRepository.findAll()).hasSize(2)
    }

    @Test
    fun `deduct는 잔액을 차감하고 음수 원장 행을 남긴다`() {
        service.reward(userId, 100, CreditReason.SIGNUP_REWARD, "signup:$userId")

        val remaining = service.deduct(userId, 30, CreditReason.STORY_CREATION, refType = "STORY", refId = 7)

        assertThat(remaining).isEqualTo(70)
        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(70)
        val consumption = transactionRepository.findAll().first { it.reason == CreditReason.STORY_CREATION }
        assertThat(consumption.amount).isEqualTo(-30)
        assertThat(consumption.refType).isEqualTo("STORY")
        assertThat(consumption.refId).isEqualTo(7)
    }

    @Test
    fun `잔액이 부족하면 deduct는 InsufficientCreditException이고 잔액이 그대로다`() {
        service.reward(userId, 100, CreditReason.SIGNUP_REWARD, "signup:$userId")

        assertThatThrownBy { service.deduct(userId, 200, CreditReason.CHAT_TURN) }
            .isInstanceOf(InsufficientCreditException::class.java)

        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(100)
        // 소모 행이 추가되지 않아야 한다(적립 1건만).
        assertThat(transactionRepository.findAll()).hasSize(1)
    }

    @Test
    fun `지갑이 없으면 deduct는 InsufficientCreditException이다`() {
        assertThatThrownBy { service.deduct(userId, 10, CreditReason.CHAT_TURN) }
            .isInstanceOf(InsufficientCreditException::class.java)
    }

    @Test
    fun `balanceOf는 지갑이 없으면 0이다`() {
        assertThat(service.balanceOf(userId)).isEqualTo(0)
    }

    @Test
    fun `balance 캐시는 원장 합계와 일치한다`() {
        service.reward(userId, 100, CreditReason.SIGNUP_REWARD, "signup:$userId")
        service.reward(userId, 50, CreditReason.ATTENDANCE_REWARD, "attendance:$userId:2026-07-05")
        service.deduct(userId, 30, CreditReason.STORY_CREATION, refType = "STORY", refId = 1)

        val ledgerSum = transactionRepository.findAll().sumOf { it.amount }
        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(ledgerSum)
        assertThat(service.balanceOf(userId)).isEqualTo(120)
    }
}
