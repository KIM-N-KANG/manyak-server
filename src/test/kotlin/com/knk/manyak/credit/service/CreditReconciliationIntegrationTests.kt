package com.knk.manyak.credit.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 크레딧 선차감 대사 배치 통합 검증(KNK-448, 스펙 §4-3-7).
 *
 * 실 DB(H2)에 소모·환불·완료 리소스를 시드하고 [CreditReconciliationService.reconcile]을 돌려,
 * 유실 환불만 보정하고 완료·기존 환불은 건드리지 않으며 재실행에도 초과 환불하지 않음을 고정한다.
 *
 * 시드한 charge는 실제 now에 만들어지므로, 시계를 미래로 둔 서비스로 대사해 "정지 상태(마지막 charge가
 * cutoff 이전)"를 재현한다. 시간가드(최신 charge 스킵)는 실제 now 시계로 검증한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class CreditReconciliationIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var transactionRepository: CreditTransactionRepository
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var storyCreationSessionRepository: StoryCreationSessionRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var structuredLogger: StructuredLogger
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private val cost = 3L
    private val threshold: Duration = Duration.ofMinutes(15)

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    // 미래 시계: 시드한 charge(실제 now)가 cutoff(미래 now − 15m) 이전이 되게 해 "정지 상태"로 대사한다.
    private fun serviceAsOf(now: Instant) = CreditReconciliationService(
        transactionRepository, creditWalletService, storyChatRepository, storyCreationSessionRepository,
        structuredLogger, threshold, Clock.fixed(now, ZoneOffset.UTC),
    )

    private val future: Instant get() = Instant.now().plus(Duration.ofHours(1))

    private fun saveUser(): Long =
        userRepository.save(User(nickname = "대사유저", status = UserStatus.ACTIVE)).id

    private fun giveBalance(userId: Long, amount: Long) {
        creditWalletService.reward(userId, amount, CreditReason.SIGNUP_REWARD, "signup:$userId")
    }

    private fun chargeChat(userId: Long, chatPk: Long) =
        creditWalletService.deduct(userId, cost, CreditReason.CHAT_TURN, refType = "CHAT", refId = chatPk)

    private fun chargeStory(userId: Long, sessionId: Long) =
        creditWalletService.deduct(userId, cost, CreditReason.STORY_CREATION, refType = "STORY", refId = sessionId)

    private fun inflightRefund(userId: Long, refType: String, refId: Long) {
        creditWalletService.reward(
            userId, cost, CreditReason.REFUND, "refund:$refType:${UUID.randomUUID()}",
            refType = refType, refId = refId,
        )
    }

    private fun seedChat(userId: Long, currentTurn: Int): Long =
        storyChatRepository.save(StoryChat(userId = userId, storyId = 1L, currentTurn = currentTurn)).id

    private fun seedSession(status: StoryCreationSessionStatus): Long =
        storyCreationSessionRepository.save(StoryCreationSession(status = status)).id

    private fun refundCount(userId: Long, refType: String, refId: Long): Long =
        transactionRepository.countByUserIdAndRefTypeAndRefIdAndReason(userId, refType, refId, CreditReason.REFUND)

    @Test
    fun `완료도 환불도 없는 채팅 턴 차감은 환불된다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0) // 저장된 턴 없음
        chargeChat(userId, chatPk)
        val afterCharge = creditWalletService.balanceOf(userId)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(1)
        assertThat(refundCount(userId, "CHAT", chatPk)).isEqualTo(1)
        assertThat(creditWalletService.balanceOf(userId)).isEqualTo(afterCharge + cost)
    }

    @Test
    fun `저장된 턴만큼 완료된 차감은 환불하지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 1) // 1턴 완료
        chargeChat(userId, chatPk)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(0)
        assertThat(refundCount(userId, "CHAT", chatPk)).isEqualTo(0)
    }

    @Test
    fun `이미 in-flight로 환불된 차감은 다시 환불하지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0)
        chargeChat(userId, chatPk)
        inflightRefund(userId, "CHAT", chatPk) // 이미 환불됨
        val afterRefund = creditWalletService.balanceOf(userId)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(0)
        assertThat(refundCount(userId, "CHAT", chatPk)).isEqualTo(1)
        assertThat(creditWalletService.balanceOf(userId)).isEqualTo(afterRefund)
    }

    @Test
    fun `여러 번 대사해도 초과 환불하지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0)
        chargeChat(userId, chatPk)

        serviceAsOf(future).reconcile()
        val afterFirst = creditWalletService.balanceOf(userId)
        val second = serviceAsOf(future).reconcile()

        assertThat(second.refundsEmitted).isEqualTo(0)
        assertThat(refundCount(userId, "CHAT", chatPk)).isEqualTo(1)
        assertThat(creditWalletService.balanceOf(userId)).isEqualTo(afterFirst)
    }

    @Test
    fun `시간가드 안의 최신 차감은 대사하지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0)
        chargeChat(userId, chatPk)

        // 실제 now 시계: 방금 만든 charge는 cutoff(now − 15m) 이후라 정지 상태가 아니다 → 스킵.
        val result = serviceAsOf(Instant.now()).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(0)
        assertThat(refundCount(userId, "CHAT", chatPk)).isEqualTo(0)
    }

    @Test
    fun `채팅 턴 일부만 완료됐으면 미완료분만 환불한다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 1) // 2차감 중 1턴만 완료
        chargeChat(userId, chatPk)
        chargeChat(userId, chatPk)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(1)
        assertThat(refundCount(userId, "CHAT", chatPk)).isEqualTo(1)
    }

    @Test
    fun `완료되지 않은 스토리 제작 차감은 환불된다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val sessionId = seedSession(StoryCreationSessionStatus.STORYLINES_GENERATED) // 스토리 미생성
        chargeStory(userId, sessionId)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(1)
        assertThat(refundCount(userId, "STORY", sessionId)).isEqualTo(1)
    }

    @Test
    fun `스토리가 생성된 세션의 차감은 환불하지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val sessionId = seedSession(StoryCreationSessionStatus.STORY_CREATED) // 스토리 생성 완료
        chargeStory(userId, sessionId)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(0)
        assertThat(refundCount(userId, "STORY", sessionId)).isEqualTo(0)
    }
}
