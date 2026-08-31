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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * CreditPolicyService 단위 검증(저장소는 mock, 시계는 테스트가 앞으로 민다).
 *
 * 캐시 TTL·만료 판정·장애 폴백처럼 시점과 쿼리 횟수에 걸린 동작을 여기서 본다.
 * yml 기본값이 실제로 붙는지는 [CreditPolicyServiceIntegrationTest]가 본다.
 */
class CreditPolicyServiceTest {

    private val repository: CreditPolicyRepository = mock(CreditPolicyRepository::class.java)
    private var now: Instant = Instant.parse("2026-08-31T00:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    // 잘못된 오버라이드가 조용히 버려지지 않는지 보려면 로그를 봐야 한다(StructuredLoggerTests 와 같은 방식).
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
        cacheTtl = CACHE_TTL,
        clock = clock,
    )

    @Test
    fun `오버라이드가 없으면 주입된 기본값을 쓴다`() {
        `when`(repository.findAll()).thenReturn(emptyList())
        val service = service()

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

        assertThat(service().amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)
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
        val service = service()
        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)

        // 캐시 TTL(60초)이 아니라 만료 시각이 지나는 순간 되돌아가야 한다 — 이벤트 종료가 최대 1분 늦으면 안 된다.
        now = now.plusSeconds(31)

        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
    }

    @Test
    fun `TTL 안에서는 여러 번 읽어도 조회는 1회다`() {
        `when`(repository.findAll()).thenReturn(emptyList())
        val service = service()

        repeat(10) { service.amountOf(CreditPolicyKey.CHAT_TURN_COST) }
        verify(repository, times(1)).findAll()

        // TTL이 지나면 다시 읽는다(운영 SQL 변경이 1분 안에 반영된다).
        now = now.plus(CACHE_TTL).plusSeconds(1)
        service.amountOf(CreditPolicyKey.CHAT_TURN_COST)
        verify(repository, times(2)).findAll()
    }

    @Test
    fun `조회가 실패해도 요청을 막지 않고 직전 스냅샷을 유지한다`() {
        `when`(repository.findAll())
            .thenReturn(listOf(CreditPolicy(policyKey = "chat_turn_cost", amount = 5)))
            .thenThrow(IllegalStateException("db down"))
        val service = service()
        assertThat(service.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(5)

        now = now.plus(CACHE_TTL).plusSeconds(1)

        assertThat(service.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(5)
    }

    @Test
    fun `스냅샷 없이 첫 조회부터 실패하면 기본값으로 떨어진다`() {
        `when`(repository.findAll()).thenThrow(IllegalStateException("db down"))

        assertThat(service().amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(20)
    }

    @Test
    fun `최소값 미만 오버라이드는 무시하고 기본값을 쓴다`() {
        // CHECK(0..10000)를 통과하는 amount=0 이 여기까지 온다. 그대로 쓰면 CreditWalletService 의
        // require(amount > 0) 가 터져 채팅 턴 전체가 500 이 된다.
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "chat_turn_cost", amount = 0)))

        assertThat(service().amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(20)
    }

    @Test
    fun `invite_monthly_cap 은 0 오버라이드를 허용한다`() {
        // 상한 0 = 초대자 적립 중단. 기존 코드가 감당하는(집계 0 >= 상한 0 → 초대자 몫만 스킵) 유효한 설정이다.
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "invite_monthly_cap", amount = 0)))

        assertThat(service().amountOf(CreditPolicyKey.INVITE_MONTHLY_CAP)).isZero()
    }

    @Test
    fun `기본값이 최소값 미만이면 부팅에 실패한다`() {
        // 오버라이드와 달리 기본값에는 물러설 곳이 없다. env 로 0 을 넣는 사고는 배포 시점에 드러나야 한다.
        assertThatThrownBy { CreditPolicyService(repository, 0, 2000, 10, 350, 200, 20, CACHE_TTL, clock) }
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
        val service = service()

        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
        // 모르는 키 하나가 전체 적재를 망치지 않는다.
        assertThat(service.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(33)
    }

    @Test
    fun `모르는 policy_key 는 warn 로그로 드러난다`() {
        // 조용히 무시하면 오타 난 정책 변경이 이벤트 기간 내내 미적용인 채 지나간다(Codex 리뷰 P2).
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "attendence_reward", amount = 700)))

        service().amountOf(CreditPolicyKey.ATTENDANCE_REWARD)

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

        service().amountOf(CreditPolicyKey.CHAT_TURN_COST)

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
        val service = service()
        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)
        appender.list.clear()

        // 만료 시각과 캐시 TTL 을 모두 지나 다음 갱신이 돌게 한다(행은 DB 에 그대로 남아 있다).
        now = now.plus(CACHE_TTL).plusSeconds(1)
        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)

        val changed = appender.list.single { "credit_policy_changed" in it.formattedMessage }
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

        service().amountOf(CreditPolicyKey.ATTENDANCE_REWARD)

        assertThat(appender.list.none { "credit_policy_changed" in it.formattedMessage }).isTrue()
    }

    @Test
    fun `캐시 TTL 이 음수면 부팅에 실패한다`() {
        // 만료 시각이 항상 과거라 모든 조회가 findAll 을 돌고 refreshLock 에서 직렬화된다 — env 오타 하나가
        // 크레딧 경로 전체를 DB 병목으로 만든다.
        assertThatThrownBy {
            CreditPolicyService(repository, 1000, 2000, 10, 350, 200, 20, Duration.ofSeconds(-1), clock)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("TTL")
    }

    @Test
    fun `캐시 TTL 0 은 허용하고 매 조회마다 재적재한다`() {
        // 테스트 프로파일이 쓰는 값이다. 0 은 "캐시 없음"이지 잘못된 설정이 아니다.
        `when`(repository.findAll()).thenReturn(emptyList())
        val service = CreditPolicyService(repository, 1000, 2000, 10, 350, 200, 20, Duration.ZERO, clock)

        service.amountOf(CreditPolicyKey.CHAT_TURN_COST)
        service.amountOf(CreditPolicyKey.CHAT_TURN_COST)

        verify(repository, times(2)).findAll()
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(60)
    }
}
