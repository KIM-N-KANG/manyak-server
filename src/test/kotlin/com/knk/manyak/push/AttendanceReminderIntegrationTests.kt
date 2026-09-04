package com.knk.manyak.push

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import com.knk.manyak.push.scheduler.AttendanceReminderScheduler
import com.knk.manyak.push.service.AttendanceReminderService
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.LocalDate
import java.time.ZoneId

/**
 * 출석 리마인드 푸시(KNK-1116, 광고성 알림).
 *
 * 대상은 ACTIVE 회원 ∩ 광고 수신 동의 ∩ 등록 토큰 보유 ∩ **당일(KST) 출석 미수령**이다.
 * 미출석 판정은 새 상태 컬럼 없이 원장의 멱등 키 `attendance:{보상 신원}:{KST 날짜}` 부재로 한다 —
 * 재가입 회원은 옛 신원 키로 기록되므로 그 키를 그대로 봐야 중복 발송을 막는다(KNK-1053).
 * 같은 날 두 번 도는 것은 Redis `SET NX`가 막는다(배포 교체로 태스크가 cron에 걸치는 창).
 */
@ActiveProfiles("test")
@SpringBootTest(
    // 스케줄러는 테스트 프로파일에서 꺼져 있다. NX 계약을 검증하려면 빈이 필요해 이 클래스에서만 켠다.
    properties = ["manyak.push.attendance-reminder.enabled=true"],
)
class AttendanceReminderIntegrationTests {

    @MockitoBean private lateinit var fcmPushSender: com.knk.manyak.push.service.FcmPushSender

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var devicePushTokenRepository: DevicePushTokenRepository
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var attendanceReminderService: AttendanceReminderService
    @Autowired private lateinit var attendanceReminderScheduler: AttendanceReminderScheduler
    @Autowired private lateinit var redisTemplate: StringRedisTemplate
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        redisTemplate.keys("push:attendance-reminder:*").orEmpty().let { if (it.isNotEmpty()) redisTemplate.delete(it) }
        reset(fcmPushSender)
    }

    private fun today(): LocalDate = LocalDate.now(SEOUL_ZONE)

    private fun saveMember(
        status: UserStatus = UserStatus.ACTIVE,
        marketingAgreed: Boolean = true,
        rewardIdentityUserId: Long? = null,
    ): User = userRepository.save(
        User(
            nickname = "수신자",
            status = status,
            marketingPushAgreedAt = if (marketingAgreed) java.time.Instant.parse("2026-09-01T00:00:00Z") else null,
            rewardIdentityUserId = rewardIdentityUserId,
        ),
    )

    private fun saveToken(user: User) {
        devicePushTokenRepository.save(
            DevicePushToken(userId = user.id, token = "tok-${user.id}", platform = PushPlatform.ANDROID),
        )
    }

    /** 출석 보상 원장 행을 심어 "오늘 이미 받았다"를 만든다(적립 API와 같은 멱등 키). */
    private fun markAttended(rewardIdentityId: Long, userId: Long) {
        creditWalletService.reward(
            userId = userId,
            amount = 100,
            reason = CreditReason.ATTENDANCE_REWARD,
            idempotencyKey = "attendance:$rewardIdentityId:${today()}",
        )
    }

    private fun eligibleMember(): User = saveMember().also { saveToken(it) }

    @Test
    fun `광고 동의와 토큰이 있고 오늘 출석하지 않은 회원에게 보낸다`() {
        val member = eligibleMember()

        val result = attendanceReminderService.sendReminders()

        assertThat(result.targets).isEqualTo(1)
        assertThat(result.sent).isEqualTo(1)
        verify(fcmPushSender).sendToUser(
            member.id,
            mapOf(
                "type" to "ATTENDANCE_REMINDER",
                "date" to today().toString(),
                "title" to "(광고) 오늘의 출석 이프를 아직 안 받았어요",
                "body" to "지금 출석하고 이프를 받아 새 이야기를 시작해 보세요.",
            ),
        )
    }

    @Test
    fun `오늘 이미 출석한 회원에게는 보내지 않는다`() {
        val member = eligibleMember()
        markAttended(rewardIdentityId = member.id, userId = member.id)

        assertThat(attendanceReminderService.sendReminders().targets).isZero()
        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `광고 수신에 동의하지 않은 회원에게는 보내지 않는다`() {
        saveMember(marketingAgreed = false).also { saveToken(it) }

        assertThat(attendanceReminderService.sendReminders().targets).isZero()
        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `등록된 토큰이 없는 회원에게는 보내지 않는다`() {
        saveMember()

        assertThat(attendanceReminderService.sendReminders().targets).isZero()
        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `정지된 회원에게는 보내지 않는다`() {
        saveMember(status = UserStatus.SUSPENDED).also { saveToken(it) }

        assertThat(attendanceReminderService.sendReminders().targets).isZero()
        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `재가입 회원의 출석이 옛 신원 키로 기록돼 있으면 보내지 않는다`() {
        // 보상 신원은 coalesce(reward_identity_user_id, id)라, 재가입 계정의 출석은 최초 계정 id로 기록된다.
        // user_id로만 판정하면 재가입한 사람이 이미 받은 날에도 리마인드를 받는다(KNK-1053).
        val original = saveMember()
        val rejoined = saveMember(rewardIdentityUserId = original.id).also { saveToken(it) }
        markAttended(rewardIdentityId = original.id, userId = rejoined.id)

        assertThat(attendanceReminderService.sendReminders().targets).isZero()
        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `스케줄러를 같은 날 두 번 돌려도 발송은 한 번뿐이다`() {
        eligibleMember()

        attendanceReminderScheduler.run()
        attendanceReminderScheduler.run()

        // 두 번째 실행은 Redis SET NX 실패로 서비스에 진입하지 않는다.
        verify(fcmPushSender).sendToUser(anyLong(), anyMap())
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
