package com.knk.manyak.credit.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * CreditPolicyService 단위 검증(저장소는 mock, 시계는 테스트가 앞으로 민다).
 *
 * 적재는 [CreditPolicyService.refresh]가 전담하고 읽기는 순수 메모리 연산이다 — 그 분리와 만료·장애·잘못된 값
 * 처리를 여기서 본다. yml 기본값이 실제로 붙는지는 [CreditPolicyServiceIntegrationTest]가 본다.
 */
class CreditPolicyServiceTest {

    private val repository: CreditPolicyRepository = mock(CreditPolicyRepository::class.java)
    private var now: Instant = Instant.parse("2026-08-31T00:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    // 잘못된 오버라이드가 조용히 버려지지 않는지, 만료가 로그에 남는지 보려면 로그를 봐야 한다
    // (StructuredLoggerTests 와 같은 방식).
    private val logger = LoggerFactory.getLogger(CreditPolicyService::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
    }

    private fun service() = CreditPolicyService(
        creditPolicyRepository = repository,
        signupReward = 1000,
        inviteReward = 2000,
        inviteMonthlyCap = 10,
        attendanceReward = 350,
        storyCreationCost = 200,
        chatTurnCost = 20,
        clock = clock,
    )

    private fun loadedService(): CreditPolicyService = service().also { it.refresh() }

    private fun changedLogs() = appender.list.filter { "credit_policy_changed" in it.formattedMessage }

    @Test
    fun `오버라이드가 없으면 주입된 기본값을 쓴다`() {
        `when`(repository.findAll()).thenReturn(emptyList())
        val service = loadedService()

        assertThat(CreditPolicyKey.entries.associateWith { service.amountOf(it) })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    CreditPolicyKey.SIGNUP_REWARD to 1000L,
                    CreditPolicyKey.INVITE_REWARD to 2000L,
                    CreditPolicyKey.INVITE_MONTHLY_CAP to 10L,
                    CreditPolicyKey.ATTENDANCE_REWARD to 350L,
                    CreditPolicyKey.STORY_CREATION_COST to 200L,
                    CreditPolicyKey.CHAT_TURN_COST to 20L,
                ),
            )
    }

    @Test
    fun `유효한 오버라이드가 있으면 그 값을 쓴다`() {
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "attendance_reward", amount = 700)))

        assertThat(loadedService().amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)
    }

    @Test
    fun `amountOf 는 DB 를 조회하지 않는다`() {
        // 크레딧 경로는 @Transactional 안에서 이 값을 읽는다. 여기서 DB I/O 가 일어나면 조회 실패가 바깥
        // 트랜잭션을 rollback-only 로 찍거나(REQUIRES_NEW 로 피하면) 두 번째 커넥션을 기다려 돈 경로가 멈춘다.
        val service = service()

        repeat(10) { CreditPolicyKey.entries.forEach { key -> service.amountOf(key) } }

        verify(repository, never()).findAll()
    }

    @Test
    fun `effective_until이 지난 오버라이드는 무시하고 기본값으로 돌아간다`() {
        `when`(repository.findAll()).thenReturn(
            listOf(
                CreditPolicy(
                    policyKey = "attendance_reward",
                    amount = 700,
                    effectiveUntil = now.plusSeconds(30),
                ),
            ),
        )
        val service = loadedService()
        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)

        // 만료 판정은 갱신이 아니라 **읽는 시점**이라, 다음 갱신 주기를 기다리지 않고 즉시 되돌아간다.
        now = now.plusSeconds(31)

        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
    }

    @Test
    fun `적재가 실패해도 요청을 막지 않고 직전 스냅샷을 유지한다`() {
        `when`(repository.findAll())
            .thenReturn(listOf(CreditPolicy(policyKey = "chat_turn_cost", amount = 5)))
            .thenThrow(IllegalStateException("db down"))
        val service = loadedService()
        assertThat(service.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(5)

        service.refresh()

        assertThat(service.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(5)
    }

    @Test
    fun `스냅샷 없이 첫 적재부터 실패하면 기본값으로 떨어진다`() {
        `when`(repository.findAll()).thenThrow(IllegalStateException("db down"))

        assertThat(loadedService().amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(20)
    }

    @Test
    fun `첫 적재가 실패해도 복구 후 오버라이드 적용이 변경 로그로 남는다`() {
        // 첫 적재 실패로 관측값이 비어 있으면, 복구 후 첫 갱신을 "최초 관측"으로 보고 전부 생략한다
        // → 시작 장애 뒤 정책이 조용히 활성화된다(Codex 리뷰 P2).
        `when`(repository.findAll())
            .thenThrow(IllegalStateException("db down"))
            .thenReturn(listOf(CreditPolicy(policyKey = "attendance_reward", amount = 700)))
        val service = loadedService()
        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
        appender.list.clear()

        service.refresh()

        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)
        assertThat(changedLogs().single().formattedMessage)
            .contains("key=attendance_reward")
            .contains("from=350")
            .contains("to=700")
    }

    @Test
    fun `최소값 미만 오버라이드는 무시하고 기본값을 쓴다`() {
        // CHECK(0..10000)를 통과하는 amount=0 이 여기까지 온다. 그대로 쓰면 CreditWalletService 의
        // require(amount > 0) 가 터져 채팅 턴 전체가 500 이 된다.
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "chat_turn_cost", amount = 0)))

        assertThat(loadedService().amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(20)
    }

    @Test
    fun `invite_monthly_cap 은 0 오버라이드를 허용한다`() {
        // 상한 0 = 초대자 적립 중단. 기존 코드가 감당하는(집계 0 >= 상한 0 → 초대자 몫만 스킵) 유효한 설정이다.
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "invite_monthly_cap", amount = 0)))

        assertThat(loadedService().amountOf(CreditPolicyKey.INVITE_MONTHLY_CAP)).isZero()
    }

    @Test
    fun `기본값이 최소값 미만이면 부팅에 실패한다`() {
        // 오버라이드와 달리 기본값에는 물러설 곳이 없다. env 로 0 을 넣는 사고는 배포 시점에 드러나야 한다.
        assertThatThrownBy { CreditPolicyService(repository, 0, 2000, 10, 350, 200, 20, clock) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("signup_reward")
    }

    @Test
    fun `모르는 policy_key 행은 무시하고 나머지 오버라이드는 정상 적용한다`() {
        `when`(repository.findAll()).thenReturn(
            listOf(
                // 운영 SQL 오타. DB CHECK 는 이걸 통과시킨다.
                CreditPolicy(policyKey = "attendence_reward", amount = 700),
                CreditPolicy(policyKey = "chat_turn_cost", amount = 33),
            ),
        )
        val service = loadedService()

        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
        // 모르는 키 하나가 전체 적재를 망치지 않는다.
        assertThat(service.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(33)
    }

    @Test
    fun `모르는 policy_key 는 warn 로그로 드러난다`() {
        // 조용히 무시하면 오타 난 정책 변경이 이벤트 기간 내내 미적용인 채 지나간다(Codex 리뷰 P2).
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "attendence_reward", amount = 700)))

        loadedService()

        val warning = appender.list.single { it.level == Level.WARN }
        assertThat(warning.formattedMessage)
            .contains("credit_policy_override_unknown_key")
            .contains("attendence_reward")
            // 유효한 키 목록이 함께 나와야 오타를 바로 잡을 수 있다.
            .contains("attendance_reward")
    }

    @Test
    fun `최소값 미만 오버라이드도 같은 레벨의 warn 으로 드러난다`() {
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "chat_turn_cost", amount = 0)))

        loadedService()

        val warning = appender.list.single { it.level == Level.WARN }
        assertThat(warning.formattedMessage).contains("credit_policy_override_rejected").contains("chat_turn_cost")
    }

    @Test
    fun `오버라이드가 만료되면 다음 갱신에서 변경 로그가 남는다`() {
        // 자동 만료가 이 기능의 존재 이유인데, 그 종료를 확인할 신호가 없으면 한시 이벤트가 실제로 끝났는지
        // 로그로 알 수 없다(Codex 리뷰 P2). 스냅샷 원본을 같은 now 로 재평가해 비교하면 만료는 영영 안 찍힌다.
        `when`(repository.findAll()).thenReturn(
            listOf(CreditPolicy(policyKey = "attendance_reward", amount = 700, effectiveUntil = now.plusSeconds(30))),
        )
        val service = loadedService()
        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)
        appender.list.clear()

        now = now.plusSeconds(31)
        service.refresh()

        val changed = changedLogs().single()
        assertThat(changed.level).isEqualTo(Level.INFO)
        assertThat(changed.formattedMessage)
            .contains("key=attendance_reward")
            .contains("from=700")
            .contains("to=350")
    }

    @Test
    fun `첫 갱신은 변경 로그를 남기지 않는다`() {
        // 비교 대상이 없다. 그 시점 전체 유효값은 부팅 로그(credit_policy_effective)가 담당한다.
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "attendance_reward", amount = 700)))

        loadedService()

        assertThat(changedLogs()).isEmpty()
    }
}
