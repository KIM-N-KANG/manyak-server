package com.knk.manyak.credit.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.port.StoryCreationProgress
import com.knk.manyak.credit.repository.CreditLotRepository
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.repository.CreditWalletRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.support.DatabaseCleaner
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
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
class CreditReconciliationIntegrationTests : IntegrationTest() {

    @Autowired private lateinit var chargeReconciler: ChatChargeReconciler
    @Autowired private lateinit var storyCreationProgress: StoryCreationProgress
    @Autowired private lateinit var transactionRepository: CreditTransactionRepository
    @Autowired private lateinit var lotRepository: CreditLotRepository
    @Autowired private lateinit var walletRepository: CreditWalletRepository
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var storyCreationSessionRepository: StoryCreationSessionRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var structuredLogger: StructuredLogger
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private val cost = 3L
    private val threshold: Duration = Duration.ofMinutes(15)

    // 혼합 단가 경고는 구조화 로그로만 나오므로 로그를 봐야 한다(StructuredLoggerTests 와 같은 방식).
    private val structuredLoggerAppender = ListAppender<ILoggingEvent>()
    private val structuredLoggerBackend = LoggerFactory.getLogger(StructuredLogger::class.java) as Logger

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        structuredLoggerAppender.start()
        structuredLoggerBackend.addAppender(structuredLoggerAppender)
    }

    @AfterEach
    fun detachAppender() {
        structuredLoggerBackend.detachAppender(structuredLoggerAppender)
    }

    private fun mixedUnitWarnings(): List<ILoggingEvent> = structuredLoggerAppender.list
        .filter { it.level == Level.WARN && "credit_reconciliation_mixed_unit_amount" in it.formattedMessage }

    // 미래 시계: 시드한 charge(실제 now)가 cutoff(미래 now − 15m) 이전이 되게 해 "정지 상태"로 대사한다.
    private fun serviceAsOf(now: Instant) = CreditReconciliationService(
        transactionRepository, creditWalletService, storyCreationProgress,
        structuredLogger, threshold, Clock.fixed(now, ZoneOffset.UTC), chargeReconciler,
    )

    private val future: Instant get() = Instant.now().plus(Duration.ofHours(1))

    private fun saveUser(): Long =
        userRepository.save(User(nickname = "대사유저", status = UserStatus.ACTIVE)).id

    private fun giveBalance(userId: Long, amount: Long) {
        creditWalletService.reward(userId, amount, CreditReason.SIGNUP_REWARD, "signup:$userId")
    }

    private fun chargeChat(userId: Long, chatPk: Long, amount: Long = cost) =
        creditWalletService.deduct(userId, amount, CreditReason.CHAT_TURN, refType = "CHAT", refId = chatPk)

    private fun chargeStory(userId: Long, sessionId: Long) =
        creditWalletService.deduct(userId, cost, CreditReason.STORY_CREATION, refType = "STORY", refId = sessionId)

    private fun inflightRefund(userId: Long, refType: String, refId: Long) {
        creditWalletService.reward(
            userId, cost, CreditReason.REFUND, if (refType == "CHAT") "refund:charge:${transactionRepository.findAll().last { it.userId == userId && it.refId == refId && it.reason == CreditReason.CHAT_TURN && !transactionRepository.existsByIdempotencyKey("refund:charge:${it.id}") }.id}" else "refund:$refType:${UUID.randomUUID()}",
            refType = refType, refId = refId,
        )
    }

    private fun seedChat(userId: Long, currentTurn: Int, regeneratedCount: Int = 0): Long =
        storyChatRepository.save(
            StoryChat(userId = userId, storyId = 1L, currentTurn = currentTurn, regeneratedCount = regeneratedCount),
        ).id

    private fun seedSession(status: StoryCreationSessionStatus): Long =
        storyCreationSessionRepository.save(StoryCreationSession(status = status)).id

    private fun refundCount(userId: Long, refType: String, refId: Long): Long =
        transactionRepository.countByUserIdAndRefTypeAndRefIdAndReason(userId, refType, refId, CreditReason.REFUND)

    @Test
    fun `이미지 차감과 환불은 턴 대사 건수에 섞이지 않는다`() {
        val user = saveUser()
        giveBalance(user, 100)
        val chat = seedChat(user, currentTurn = 0)
        chargeChat(user, chat)
        creditWalletService.deduct(user, 30, CreditReason.CHAT_IMAGE, "CHAT_IMAGE", chat)
        creditWalletService.reward(user, 30, CreditReason.REFUND, "image-refund", "CHAT_IMAGE", chat)
        serviceAsOf(future).reconcile()
        serviceAsOf(future).reconcile()
        assertThat(refundCount(user, "CHAT", chat)).isEqualTo(1)
        assertThat(refundCount(user, "CHAT_IMAGE", chat)).isEqualTo(1)
        assertThat(creditWalletService.balanceOf(user)).isEqualTo(100)
    }

    @Test
    fun `이미지 차감만 있으면 대사 배치가 자동 환불하지 않는다`() {
        val user = saveUser()
        giveBalance(user, 100)
        val chat = seedChat(user, currentTurn = 0)
        creditWalletService.deduct(user, 30, CreditReason.CHAT_IMAGE, "CHAT_IMAGE", chat)
        serviceAsOf(future).reconcile()
        assertThat(refundCount(user, "CHAT_IMAGE", chat)).isZero()
        assertThat(creditWalletService.balanceOf(user)).isEqualTo(70)
    }

    @Test
    fun `혼합 단가 그룹에 환불을 발행하면 경고를 남긴다`() {
        // KNK-1056: 정책 오버라이드가 채팅 수명 도중 바뀌면 그룹 안에 서로 다른 차감액이 섞인다.
        // 환불은 최소액으로 나가 회원이 차액만큼 미보상되므로, 그 사실이 조용히 지나가면 안 된다.
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0)
        chargeChat(userId, chatPk, amount = 3)
        chargeChat(userId, chatPk, amount = 20)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(2)
        val warning = mixedUnitWarnings().single()
        assertThat(warning.formattedMessage)
            .contains("min_unit_amount=3")
            .contains("max_unit_amount=20")
            .contains("refunds_emitted=2")
    }

    @Test
    fun `혼합 단가여도 환불이 발행되지 않으면 경고하지 않는다`() {
        // 오경보 차단(Codex 리뷰 P2). in-flight 경로가 이미 각 차감액대로 정확히 환불한 그룹도
        // targetRefundCount 는 양수로 남는다 — 발행 여부는 reconcileRefunds 안에서야 판정되므로,
        // 그 앞에서 경고하면 미보상이 없는 그룹이 매 배치마다 같은 오경보를 반복한다.
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0)
        chargeChat(userId, chatPk, amount = 3)
        chargeChat(userId, chatPk, amount = 20)
        inflightRefund(userId, "CHAT", chatPk)
        inflightRefund(userId, "CHAT", chatPk)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(0)
        assertThat(mixedUnitWarnings()).isEmpty()
    }

    @Test
    fun `균일 단가 그룹은 환불을 발행해도 경고하지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0)
        chargeChat(userId, chatPk)

        assertThat(serviceAsOf(future).reconcile().refundsEmitted).isEqualTo(1)
        assertThat(mixedUnitWarnings()).isEmpty()
    }

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
    fun `저장된 턴과 완료된 재생성만큼 차감된 채팅은 환불하지 않는다`() {
        // KNK-406 회귀: 재생성은 CHAT_TURN을 차감하지만 current_turn을 올리지 않는다. 완료 수 = current_turn +
        // regeneratedCount로 세야 성공한 재생성이 대사에서 미완료로 오인돼 초과 환불되지 않는다.
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 1, regeneratedCount = 1) // 1턴 + 1재생성 완료
        chargeChat(userId, chatPk) // 턴 차감
        chargeChat(userId, chatPk) // 재생성 차감

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(0)
        assertThat(refundCount(userId, "CHAT", chatPk)).isEqualTo(0)
    }

    @Test
    fun `완료된 재생성은 완료 수에 포함되어 미완료 재생성분만 환불한다`() {
        // 완료 수(current_turn 1 + regeneratedCount 1 = 2)를 초과한 3번째 차감(유실된 재생성)만 환불한다.
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 1, regeneratedCount = 1)
        chargeChat(userId, chatPk) // 턴
        chargeChat(userId, chatPk) // 완료된 재생성
        chargeChat(userId, chatPk) // 유실된 재생성(미완료)

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
    fun `제작 세션이 없는 차감은 환불하지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val missingSessionId = 999_999L
        chargeStory(userId, missingSessionId)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.groupsScanned).isEqualTo(1)
        assertThat(result.refundsEmitted).isEqualTo(0)
        assertThat(refundCount(userId, "STORY", missingSessionId)).isEqualTo(0)
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

    // 차감 1건 그룹에서는 완료 수 1과 NEVER_REFUND가 같은 결과(환불 0)라 판정 매핑을 구분하지 못한다. 2건으로 구분한다.
    @Test
    fun `제작 세션이 없으면 차감이 둘이어도 환불하지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val missingSessionId = 999_999L
        chargeStory(userId, missingSessionId)
        chargeStory(userId, missingSessionId)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(0)
        assertThat(refundCount(userId, "STORY", missingSessionId)).isEqualTo(0)
    }

    @Test
    fun `스토리가 생성된 세션에 차감이 둘이면 하나만 환불한다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val sessionId = seedSession(StoryCreationSessionStatus.STORY_CREATED)
        chargeStory(userId, sessionId)
        chargeStory(userId, sessionId)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(1)
        assertThat(refundCount(userId, "STORY", sessionId)).isEqualTo(1)
    }

    @Test
    fun `모자란 환불은 그룹 최소 차감액으로 발행하고 환불 로트를 만든다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0)
        chargeChat(userId, chatPk, amount = 20) // 큰 차감을 먼저 둬서 "첫 차감액"이 아니라 최소액임을 가린다
        chargeChat(userId, chatPk)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(2)
        val refunds = transactionRepository.findAll().filter { it.reason == CreditReason.REFUND }
        assertThat(refunds).hasSize(2).allSatisfy { refund ->
            assertThat(refund.amount).isEqualTo(cost)
            assertThat(refund.refType).isEqualTo("CHAT")
            assertThat(refund.refId).isEqualTo(chatPk)
            assertThat(refund.idempotencyKey).isNull()
        }
        val lots = lotRepository.findByTransactionIdIn(refunds.map { it.id })
        assertThat(lots).hasSize(2).allSatisfy { lot ->
            assertThat(lot.originalAmount).isEqualTo(cost)
            assertThat(lot.remaining).isEqualTo(cost)
            assertThat(lot.expiresAt).isNotNull()
        }
    }

    @Test
    fun `일부만 환불된 그룹은 부족분만 발행한다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val chatPk = seedChat(userId, currentTurn = 0) // 3차감 모두 미완료, 그중 1건만 즉시 환불됨
        repeat(3) { chargeChat(userId, chatPk) }
        inflightRefund(userId, "CHAT", chatPk)
        val afterInflight = creditWalletService.balanceOf(userId)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.refundsEmitted).isEqualTo(2)
        assertThat(refundCount(userId, "CHAT", chatPk)).isEqualTo(3)
        assertThat(creditWalletService.balanceOf(userId)).isEqualTo(afterInflight + 2 * cost)
    }

    @Test
    fun `지갑이 없는 회원의 그룹은 환불하지 않는다`() {
        val userId = saveUser()

        val emitted = creditWalletService.reconcileRefunds(userId, "STORY", 999_999L, cost, targetRefundCount = 1)

        assertThat(emitted).isEqualTo(0)
        assertThat(refundCount(userId, "STORY", 999_999L)).isEqualTo(0)
    }

    @Test
    fun `두 대사가 함께 잠금 전 확인을 통과해도 잠금 뒤 재확인으로 부족분만큼만 환불한다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val sessionId = seedSession(StoryCreationSessionStatus.STORYLINES_GENERATED)
        chargeStory(userId, sessionId)
        // 두 스레드가 지갑 잠금 직전에서 만나야 넘어간다. 한쪽이 잠금 전 확인에서 끝나면 장벽이 시간 초과로 깨진다.
        // 그래서 "둘 다 잠금 전 환불 수 0을 보고 지갑 잠금에서 직렬화되는" 인터리빙이 매번 만들어진다.
        val atLock = java.util.concurrent.CyclicBarrier(2)
        // 리포지토리는 JDK 프록시라 callRealMethod가 안 된다. 스파이의 기본 응답(실제 빈 위임)으로 넘긴다.
        val delegate = org.mockito.Mockito.mockingDetails(walletRepository).mockCreationSettings.defaultAnswer
        org.mockito.Mockito.doAnswer { call ->
            atLock.await(10, java.util.concurrent.TimeUnit.SECONDS)
            delegate.answer(call)
        }.`when`(walletRepository).findByUserIdForUpdate(userId)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val runs = List(2) {
                executor.submit(java.util.concurrent.Callable {
                    creditWalletService.reconcileRefunds(userId, "STORY", sessionId, cost, targetRefundCount = 1)
                })
            }
            assertThat(runs.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }).containsExactlyInAnyOrder(1, 0)
        } finally {
            executor.shutdownNow()
        }
        assertThat(refundCount(userId, "STORY", sessionId)).isEqualTo(1)
        assertThat(creditWalletService.balanceOf(userId)).isEqualTo(100)
    }

    @Test
    fun `환불이 이미 충분한 그룹은 지갑을 잠그지 않는다`() {
        val userId = saveUser()
        giveBalance(userId, 100)
        val sessionId = seedSession(StoryCreationSessionStatus.STORYLINES_GENERATED)
        chargeStory(userId, sessionId)
        inflightRefund(userId, "STORY", sessionId) // 실패 뒤 정상 환불된 그룹: 있어야 할 환불 수 1을 이미 채움
        org.mockito.Mockito.clearInvocations(walletRepository)

        val result = serviceAsOf(future).reconcile()

        assertThat(result.groupsScanned).isEqualTo(1)
        assertThat(result.refundsEmitted).isEqualTo(0)
        org.mockito.Mockito.verify(walletRepository, org.mockito.Mockito.never()).findByUserIdForUpdate(userId)
        assertThat(refundCount(userId, "STORY", sessionId)).isEqualTo(1)
    }
}
