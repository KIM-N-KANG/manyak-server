package com.knk.manyak.credit.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.chat.service.*
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
class ChatChargeReconciliationIntegrationTests {
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    @Autowired private lateinit var versions: StoryMessageVersionRepository
    @Autowired private lateinit var transactions: CreditTransactionRepository
    @Autowired private lateinit var wallet: CreditWalletService
    @Autowired private lateinit var reconciliation: CreditReconciliationService
    @Autowired private lateinit var persister: ChatTurnPersister
    @Autowired private lateinit var rowReconciler: ChatChargeReconciler
    @Autowired private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    private var userId = 0L
    private var chatId = 0L

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        userId = users.save(User(nickname = "대사회원", status = UserStatus.ACTIVE)).id
        chatId = chats.save(StoryChat(storyId = stories.save(Story(title = "대사 스토리")).id, userId = userId)).id
        wallet.reward(userId, 1000, CreditReason.SIGNUP_REWARD, "signup:$userId")
    }
    private fun charge(amount: Long, rowBased: Boolean = true): Long {
        wallet.deduct(userId, amount, CreditReason.CHAT_TURN, if (rowBased) "CHAT_TURN" else "CHAT", chatId)
        val id = transactions.findAll().last { it.reason == CreditReason.CHAT_TURN }.id
        jdbc.update("UPDATE credit_transactions SET created_at = DATEADD('HOUR', -1, CURRENT_TIMESTAMP) WHERE id = ?", id)
        return id
    }

    private fun completed(total: Int, regenerated: Int = 0) {
        chats.save(chats.findById(chatId).orElseThrow().apply { currentTurn = total; regeneratedCount = regenerated })
    }
    private fun linked(id: Long, order: Int = 2): StoryMessage = messages.save(StoryMessage(
        chatId = chatId, role = MessageRole.ASSISTANT, content = "완료", messageOrder = order, creditTransactionId = id,
    ))

    @Test fun `새 턴 차감은 CHAT_TURN 참조와 NULL 멱등 키로 기록한다`() {
        val id = charge(50)
        val row = transactions.findById(id).orElseThrow()
        assertThat(row.refType).isEqualTo("CHAT_TURN")
        assertThat(row.idempotencyKey).isNull()
    }

    @Test fun `구버전 그룹 대사와 새 행 대사는 양쪽 실행 순서에서 한 번만 환불한다`() {
        for (oldFirst in listOf(true, false)) {
            val id = charge(50)
            // origin/dev 쿼리는 새 ref_type도 그룹으로 읽지만 completedCount의 else는
            // Long.MAX_VALUE라 미지원 참조에 reconcileRefunds를 호출하지 않는다.
            fun oldReconcile() {
                val groups = jdbc.queryForList("SELECT ref_type, ref_id, COUNT(*) AS charges FROM credit_transactions WHERE user_id = ? AND reason = 'CHAT_TURN' GROUP BY ref_type, ref_id", userId)
                assertThat(groups.any { it["ref_type"] == "CHAT_TURN" }).isTrue()
                for (group in groups) {
                    val refType = group["ref_type"].toString()
                    val completed = if (refType == "CHAT") chats.findById(chatId).orElseThrow().let { it.currentTurn.toLong() + it.regeneratedCount } else Long.MAX_VALUE
                    val target = (group["charges"] as Number).toLong() - completed
                    if (target > 0) wallet.reconcileRefunds(userId, refType, chatId, 50, target)
                }
            }
            if (oldFirst) oldReconcile()
            assertThat(reconciliation.reconcile().refundsEmitted).isEqualTo(1)
            if (!oldFirst) oldReconcile()
            assertThat(reconciliation.reconcile().refundsEmitted).isZero()
            assertThat(transactions.findByIdempotencyKey("refund:charge:$id")!!.refType).isEqualTo("CHAT_TURN")
            assertThat(wallet.balanceOf(userId)).isEqualTo(1000)
        }
    }

    @Test fun `혼재 배포에서 구버전 CHAT 참조 정상 완료 차감은 행 환불 대상이 아니다`() {
        charge(20, rowBased = false)
        completed(1)
        val result = reconciliation.reconcile()
        assertThat(result.chargesScanned).isZero()
        assertThat(result.refundsEmitted).isZero()
        assertThat(wallet.balanceOf(userId)).isEqualTo(980)
    }

    @Test fun `두 시기 혼재 채팅은 새 코드의 완료를 빼고 과거 유실만 그룹 환불한다`() {
        charge(20, rowBased = false); charge(20, rowBased = false)
        linked(charge(50))
        completed(2)
        val result = reconciliation.reconcile()
        assertThat(result.refundsEmitted).isEqualTo(1)
        assertThat(wallet.balanceOf(userId)).isEqualTo(930)
        assertThat(reconciliation.reconcile().refundsEmitted).isZero()
    }

    @Test fun `CHAT_TURN 차감의 즉시 환불은 CHAT 유실 환불 수에서 제외한다`() {
        charge(20, rowBased = false)
        val newCharge = charge(50)
        wallet.reward(userId, 50, CreditReason.REFUND, "refund:charge:$newCharge", "CHAT_TURN", chatId)
        assertThat(reconciliation.reconcile().refundsEmitted).isEqualTo(1)
        assertThat(wallet.balanceOf(userId)).isEqualTo(1000)
    }

    @Test fun `CHAT 차감의 즉시 환불과 NULL 키 그룹 환불을 이미 환불된 것으로 센다`() {
        val old = charge(20, rowBased = false)
        charge(20, rowBased = false); charge(20, rowBased = false)
        wallet.reward(userId, 20, CreditReason.REFUND, "refund:charge:$old", "CHAT", chatId)
        wallet.reward(userId, 20, CreditReason.REFUND, "group-fixture", "CHAT", chatId)
        jdbc.update("UPDATE credit_transactions SET idempotency_key = NULL WHERE idempotency_key = 'group-fixture'")
        assertThat(reconciliation.reconcile().refundsEmitted).isEqualTo(1)
    }

    @Test fun `과거 UUID 키 환불은 이미 지급된 CHAT 환불로 세어 추가 지급하지 않는다`() {
        charge(20, rowBased = false)
        wallet.reward(userId, 20, CreditReason.REFUND, "refund:chatturn:${java.util.UUID.randomUUID()}", "CHAT", chatId)
        assertThat(reconciliation.reconcile().refundsEmitted).isZero()
        assertThat(wallet.balanceOf(userId)).isEqualTo(1000)
    }

    @Test fun `롤백 이미지 차감은 그룹 규칙으로 세고 남은 체험 완료는 보수적으로 유지한다`() {
        linked(charge(50))
        charge(20, rowBased = false); charge(20, rowBased = false)
        // 스펙의 전체 완료 3에는 차감이 없는 체험 완료가 포함되어 그룹 완료가 3-1=2다.
        completed(3)
        assertThat(reconciliation.reconcile().refundsEmitted).isZero()
    }

    @Test fun `새 코드의 재생성 이력 완료도 과거 그룹 완료 수에서 제외한다`() {
        charge(20, rowBased = false); charge(20, rowBased = false)
        val message = linked(charge(20))
        versions.save(StoryMessageVersion(messageId = message.id, versionNumber = 1, content = "이전", choices = "[]", creditTransactionId = charge(50)))
        completed(2, 1)
        assertThat(reconciliation.reconcile().refundsEmitted).isEqualTo(1)
    }

    private fun bulkCharges(count: Int): List<Long> {
        wallet.reward(userId, count.toLong(), CreditReason.SIGNUP_REWARD, "bulk:$count")
        val charges = wallet.deductBatch(userId, List(count) { CreditDeduction(1, CreditReason.CHAT_TURN, "CHAT_TURN", chatId) }).transactions
        for (charge in charges) jdbc.update("UPDATE credit_transactions SET created_at = DATEADD('HOUR', -1, CURRENT_TIMESTAMP) WHERE id = ?", charge.id)
        return charges.map { it.id }
    }

    @Test fun `연결된 천 건과 환불된 행은 후보에서 제외하고 유실 한 건만 잠근다`() {
        val ids = bulkCharges(1002)
        messages.saveAll(ids.take(1000).mapIndexed { index, id -> StoryMessage(chatId = chatId, role = MessageRole.ASSISTANT, content = "완료", messageOrder = index + 1, creditTransactionId = id) })
        wallet.reward(userId, 1, CreditReason.REFUND, "refund:charge:${ids[1000]}", "CHAT_TURN", chatId)
        val result = reconciliation.reconcile()
        assertThat(result.chargesScanned).isEqualTo(1)
        assertThat(result.refundsEmitted).isEqualTo(1)
    }

    @Test fun `유실 육백 건은 한 실행에서 오백 행 페이지로 모두 환불한다`() {
        bulkCharges(600)
        val first = reconciliation.reconcile()
        assertThat(first.chargesScanned).isEqualTo(600)
        assertThat(first.refundsEmitted).isEqualTo(600)
        val second = reconciliation.reconcile()
        assertThat(second.chargesScanned).isZero()
        assertThat(second.refundsEmitted).isZero()
        assertThat(reconciliation.reconcile().chargesScanned).isZero()
    }

    @Test fun `대사가 차감 행 잠금을 먼저 잡으면 대기 중 저장은 환불을 확인하고 폐기한다`() = race(refundFirst = true)

    @Test fun `저장이 차감 행 잠금을 먼저 잡으면 대기 중 대사는 연결을 확인하고 건너뛴다`() = race(refundFirst = false)

    private fun race(refundFirst: Boolean) {
        val id = charge(50)
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val competing = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val tx = org.springframework.transaction.support.TransactionTemplate(transactionManager)
        fun save() = persister.persistTurn(chatId, "입력", "출력", emptyList(), judgmentSource = TurnJudgmentSource(), expectedState = GenerationStartState(null, 0), creditTransactionId = id)
        try {
            val first = executor.submit(java.util.concurrent.Callable {
                tx.execute {
                    if (refundFirst) rowReconciler.reconcile(id) else save()
                    locked.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            })
            check(locked.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit(java.util.concurrent.Callable {
                competing.countDown()
                if (refundFirst) {
                    assertThatThrownBy { save() }.isInstanceOf(IllegalStateException::class.java)
                } else {
                    assertThat(rowReconciler.reconcile(id)).isFalse()
                }
            })
            check(competing.await(10, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            first.get(10, java.util.concurrent.TimeUnit.SECONDS)
            second.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(messages.count()).isEqualTo(if (refundFirst) 0L else 2L)
            assertThat(wallet.balanceOf(userId)).isEqualTo(if (refundFirst) 1000L else 950L)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test fun `혼합 모델에서 유실된 오로라 차감만 정확히 50을 환불하고 메시지와 버전 연결은 보존한다`() {
        val lost = charge(50)
        val archived = charge(20)
        val active = charge(20)
        val message = messages.save(StoryMessage(chatId = chatId, role = MessageRole.ASSISTANT, content = "활성", messageOrder = 2, creditTransactionId = active))
        versions.save(StoryMessageVersion(messageId = message.id, versionNumber = 1, content = "이전", choices = "[]", creditTransactionId = archived))
        val result = reconciliation.reconcile()
        assertThat(result.chargesScanned).isEqualTo(1)
        assertThat(result.refundsEmitted).isEqualTo(1)
        assertThat(transactions.findByIdempotencyKey("refund:charge:$lost")!!.amount).isEqualTo(50)
        assertThat(wallet.balanceOf(userId)).isEqualTo(960)
        assertThat(reconciliation.reconcile().refundsEmitted).isZero()
    }

    @Test fun `즉시 환불과 대사는 같은 멱등 키를 쓰고 이미 환불된 차감은 저장할 수 없다`() {
        val id = charge(50)
        wallet.reward(userId, 50, CreditReason.REFUND, "refund:charge:$id", "CHAT_TURN", chatId)
        assertThat(reconciliation.reconcile().refundsEmitted).isZero()
        assertThatThrownBy {
            persister.persistTurn(chatId, "입력", "늦은 출력", emptyList(), judgmentSource = TurnJudgmentSource(), expectedState = GenerationStartState(null, 0), creditTransactionId = id)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(messages.findAll()).isEmpty()
        assertThat(wallet.balanceOf(userId)).isEqualTo(1000)
    }

    @Test fun `저장이 먼저 완료된 차감과 기준 시각 이내 차감은 환불하지 않는다`() {
        val id = charge(50)
        persister.persistTurn(chatId, "입력", "출력", emptyList(), judgmentSource = TurnJudgmentSource(), expectedState = GenerationStartState(null, 0), creditTransactionId = id)
        wallet.deduct(userId, 20, CreditReason.CHAT_TURN, "CHAT_TURN", chatId)
        assertThat(reconciliation.reconcile().refundsEmitted).isZero()
        assertThat(wallet.balanceOf(userId)).isEqualTo(930)
    }
}
