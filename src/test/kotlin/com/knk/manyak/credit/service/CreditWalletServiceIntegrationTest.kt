package com.knk.manyak.credit.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
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
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.ZoneId

/**
 * CreditWalletService의 기반 연산 통합 검증(스펙 §4-3-7 원장과 동시성).
 *
 * - reward: 지갑 생성·적립, 멱등 키 중복 시 미적립(rewarded=false), 잔액 = 원장 합계.
 * - deduct: 잔액 차감·음수 원장 행, 잔액 부족/지갑 부재 시 InsufficientCreditException.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreditWalletServiceIntegrationTest {


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
    fun `hasTransaction은 멱등 키로 적립 여부를 확인하고 부수효과가 없다`() {
        assertThat(service.hasTransaction("attendance:$userId:2026-07-08")).isFalse()

        service.reward(userId, 250, CreditReason.ATTENDANCE_REWARD, "attendance:$userId:2026-07-08")

        assertThat(service.hasTransaction("attendance:$userId:2026-07-08")).isTrue()
        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(250)
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

    @Test
    fun `월 상한 미만이면 적립하고 상한에 도달하면 같은 구간 적립을 건너뛴다`() {
        // 초대 보상 월 상한(스펙 §4-3-7): 지갑 락 안에서 구간 집계가 상한 미만일 때만 적립한다. 상한 3으로 좁혀 경계를 확인한다.
        // 구간은 **실행 시점의 KST 월**로 잡는다. 아래 적립이 만드는 원장 행의 created_at은 실제 now()라,
        // 고정 날짜 구간을 쓰면 그 달이 지나는 순간 행이 구간 밖으로 나가 집계가 0이 되고 상한에 걸리지 않는다(KNK-753).
        val (monthStart, monthEnd) = kstMonthRangeOf(Instant.now())
        val window = MonthlyRewardCap(
            reason = CreditReason.INVITE_REWARD,
            cap = 3,
            windowStart = monthStart,
            windowEnd = monthEnd,
        )

        // 서로 다른 멱등 키로 3회까지는 적립된다(구간 내 count가 0→1→2에서 상한 3 미만).
        repeat(3) { i ->
            val outcome = service.reward(userId, 500, CreditReason.INVITE_REWARD, "invite:x:$i:$userId", monthlyCap = window)
            assertThat(outcome.rewarded).isTrue()
        }

        // 4번째는 구간 count가 이미 3(=상한)이라 적립하지 않는다(오류 아님, rewarded=false·잔액 불변).
        val capped = service.reward(userId, 500, CreditReason.INVITE_REWARD, "invite:x:3:$userId", monthlyCap = window)
        assertThat(capped.rewarded).isFalse()
        assertThat(capped.balance).isEqualTo(1500)

        val inviteRows = transactionRepository.findAll().filter { it.reason == CreditReason.INVITE_REWARD }
        assertThat(inviteRows).hasSize(3)
        assertThat(walletRepository.findByUserId(userId)!!.balance).isEqualTo(1500)
    }

    @Test
    fun `구간 밖의 기존 적립은 월 상한 집계에 포함되지 않는다`() {
        // 지난달(구간 밖)에 상한만큼 적립돼 있어도, 이번 달 구간 집계는 0이라 이번 달 적립은 통과해야 한다(KST 월 경계 검증).
        val pastKey = "invite:old:1:$userId"
        service.reward(userId, 500, CreditReason.INVITE_REWARD, pastKey)
        // 위 적립 행의 createdAt은 now(테스트 시각)이라, 창을 **다음 KST 월**로 잡아 "구간 밖 과거"를 흉내낸다.
        // 고정 미래 날짜(예: 2027-08)를 쓰면 그 달이 오는 순간 now()가 창 안으로 들어와 테스트가 깨진다(KNK-753 — 위 테스트와 같은 결함).
        val nextMonthStart = kstMonthRangeOf(Instant.now()).second
        val (windowStart, windowEnd) = kstMonthRangeOf(nextMonthStart)
        val futureWindow = MonthlyRewardCap(
            reason = CreditReason.INVITE_REWARD,
            cap = 1,
            windowStart = windowStart,
            windowEnd = windowEnd,
        )

        val outcome = service.reward(userId, 500, CreditReason.INVITE_REWARD, "invite:new:1:$userId", monthlyCap = futureWindow)

        // 창 밖(과거) 적립은 집계에서 빠지므로 상한 1이어도 이번 적립은 통과한다.
        assertThat(outcome.rewarded).isTrue()
        assertThat(transactionRepository.findAll().count { it.reason == CreditReason.INVITE_REWARD }).isEqualTo(2)
    }

    /**
     * [instant]가 속한 KST 월의 [시작, 다음달 시작) 구간을 돌려준다. 월 상한 구간의 **기준 시간대는 KST**이며,
     * 이는 유일한 프로덕션 호출부인 `InviteService.kstMonthRangeOf`와 같은 계산이다(경계가 UTC면 KST 기준 월초·월말
     * 9시간이 어긋난 구간을 검증하게 된다).
     *
     * 원장 행의 created_at은 [com.knk.manyak.credit.entity.CreditTransaction]의 기본값 `Instant.now()`라
     * 서비스에 고정 Clock을 주입해도 바뀌지 않는다. 그래서 이 테스트는 고정 시계 대신 실제 now() 기준으로
     * 구간을 계산해 데이터와 경계를 같은 시간축에 맞춘다(KNK-753).
     */
    private fun kstMonthRangeOf(instant: Instant): Pair<Instant, Instant> {
        val monthStartDate = instant.atZone(SEOUL_ZONE).toLocalDate().withDayOfMonth(1)
        return monthStartDate.atStartOfDay(SEOUL_ZONE).toInstant() to
            monthStartDate.plusMonths(1).atStartOfDay(SEOUL_ZONE).toInstant()
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
