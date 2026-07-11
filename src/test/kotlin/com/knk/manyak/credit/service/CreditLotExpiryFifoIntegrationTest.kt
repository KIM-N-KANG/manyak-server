package com.knk.manyak.credit.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.entity.CreditLot
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditWallet
import com.knk.manyak.credit.repository.CreditLotRepository
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
import java.time.Duration
import java.time.Instant

/**
 * 보상 크레딧 30일 만료·FIFO 차감 검증(스펙 §4-3-7, B12).
 *
 * - reward: 보상 로트는 적립 시점 + 30일 만료, 무기한(PURCHASE)만 만료 없음.
 * - deduct: 만료 임박(무기한은 마지막)·먼저 적립된 로트부터 소진하고, 만료 로트는 EXPIRE 행으로 회수한다.
 * - balanceOf: 만료 잔여를 제외한 활성 로트 합.
 *
 * 만료 경계는 로트의 `expiresAt`을 과거·근접·먼 미래로 직접 심어 실시간 시계 기준으로 검증한다(고정 시계 불필요).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreditLotExpiryFifoIntegrationTest {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var service: CreditWalletService
    @Autowired private lateinit var walletRepository: CreditWalletRepository
    @Autowired private lateinit var lotRepository: CreditLotRepository
    @Autowired private lateinit var transactionRepository: CreditTransactionRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private var userId: Long = 0

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        userId = userRepository.save(User(nickname = "로트유저", status = UserStatus.ACTIVE)).id
    }

    /** 활성 지갑과 로트를 직접 심는다. wallet.balance는 sweep 이전 총 잔여(만료분 포함)로 둔다. */
    private fun seedWallet(balance: Long) {
        walletRepository.save(CreditWallet(userId = userId, balance = balance))
    }

    private fun seedLot(remaining: Long, expiresAt: Instant?): CreditLot =
        lotRepository.save(
            CreditLot(
                userId = userId,
                transactionId = null,
                originalAmount = remaining,
                remaining = remaining,
                expiresAt = expiresAt,
            ),
        )

    @Test
    fun `reward는 보상 로트에 30일 만료를 설정한다`() {
        val before = Instant.now()
        service.reward(userId, 100, CreditReason.SIGNUP_REWARD, "signup:$userId")

        val lot = lotRepository.findAll().single()
        assertThat(lot.originalAmount).isEqualTo(100)
        assertThat(lot.remaining).isEqualTo(100)
        assertThat(lot.expiresAt).isNotNull()
        assertThat(lot.expiresAt).isBetween(before.plus(Duration.ofDays(29)), before.plus(Duration.ofDays(31)))
    }

    @Test
    fun `deduct는 만료 임박 로트부터 FIFO로 소진한다`() {
        seedWallet(150)
        val soon = seedLot(remaining = 100, expiresAt = Instant.now().plus(Duration.ofDays(10)))
        val later = seedLot(remaining = 50, expiresAt = Instant.now().plus(Duration.ofDays(40)))

        val balance = service.deduct(userId, 120, CreditReason.CHAT_TURN, refType = "CHAT", refId = 1)

        assertThat(balance).isEqualTo(30)
        assertThat(lotRepository.findById(soon.id).get().remaining).isEqualTo(0)
        assertThat(lotRepository.findById(later.id).get().remaining).isEqualTo(30)
        assertThat(service.balanceOf(userId)).isEqualTo(30)
    }

    @Test
    fun `deduct는 만료 로트를 EXPIRE로 회수하고 미만료만 소진한다`() {
        seedWallet(150)
        val expired = seedLot(remaining = 100, expiresAt = Instant.now().minus(Duration.ofDays(1)))
        val active = seedLot(remaining = 50, expiresAt = Instant.now().plus(Duration.ofDays(40)))

        // 만료분을 뺀 활성 잔액은 50뿐이다.
        assertThat(service.balanceOf(userId)).isEqualTo(50)

        val balance = service.deduct(userId, 30, CreditReason.CHAT_TURN, refType = "CHAT", refId = 2)

        assertThat(balance).isEqualTo(20)
        assertThat(lotRepository.findById(expired.id).get().remaining).isEqualTo(0)
        assertThat(lotRepository.findById(active.id).get().remaining).isEqualTo(20)
        // 만료 회수 EXPIRE 행(-100)과 소모 CHAT_TURN 행(-30)이 남는다.
        val expireRow = transactionRepository.findAll().single { it.reason == CreditReason.EXPIRE }
        assertThat(expireRow.amount).isEqualTo(-100)
        assertThat(expireRow.refType).isEqualTo("CREDIT_LOT")
        assertThat(expireRow.refId).isEqualTo(expired.id)
        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(20)
    }

    @Test
    fun `만료로 활성 잔액이 부족하면 deduct는 InsufficientCreditException이고 만료 정리를 쓰지 않는다`() {
        seedWallet(100)
        val expired = seedLot(remaining = 100, expiresAt = Instant.now().minus(Duration.ofDays(1)))

        assertThatThrownBy { service.deduct(userId, 30, CreditReason.CHAT_TURN) }
            .isInstanceOf(InsufficientCreditException::class.java)

        // 만료 크레딧은 소진 대상이 아니라 활성 잔액은 0이다.
        assertThat(service.balanceOf(userId)).isEqualTo(0)
        // 부족 판정은 만료 정리(쓰기) 이전에 던지므로, 롤백될 EXPIRE 행을 남기지 않고 로트 잔여도 그대로다(반복 재처리 방지).
        assertThat(transactionRepository.findAll().none { it.reason == CreditReason.EXPIRE }).isTrue()
        assertThat(lotRepository.findById(expired.id).get().remaining).isEqualTo(100)
    }

    @Test
    fun `무기한 로트는 만료되지 않고 만료 있는 로트 다음에 소진된다`() {
        seedWallet(150)
        val reward = seedLot(remaining = 100, expiresAt = Instant.now().plus(Duration.ofDays(10)))
        val unlimited = seedLot(remaining = 50, expiresAt = null)

        val balance = service.deduct(userId, 120, CreditReason.CHAT_TURN, refType = "CHAT", refId = 3)

        assertThat(balance).isEqualTo(30)
        assertThat(lotRepository.findById(reward.id).get().remaining).isEqualTo(0)
        assertThat(lotRepository.findById(unlimited.id).get().remaining).isEqualTo(30)
        assertThat(service.balanceOf(userId)).isEqualTo(30)
    }
}
