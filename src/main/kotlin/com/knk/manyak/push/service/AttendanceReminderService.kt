package com.knk.manyak.push.service

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 한 회차의 발송 결과. [targets]는 조회된 대상 수, [sent]는 실제로 발송기를 부른 수,
 * [skipped]는 조회 이후 자격을 잃어(동의 철회·정지·탈퇴·야간) 건너뛴 수다.
 */
data class AttendanceReminderResult(val targets: Int, val sent: Int, val skipped: Int)

/**
 * 출석 리마인드 푸시(KNK-1116). **광고성 알림**이라 사전 동의가 필수다(정보통신망법 제50조, 정책 KNK-1129).
 *
 * 대상은 `ACTIVE` ∩ 광고 동의 ∩ 등록 토큰 보유 ∩ 당일(KST) 출석 미수령이며, 판정은 전부
 * [UserRepository.findAttendanceReminderTargetIds] 한 쿼리가 한다(새 상태 컬럼 없음 — 원장의 멱등 키 부재).
 *
 * **동의는 발송 직전에 다시 읽는다**(Codex 리뷰 P1). 대상 조회 결과는 조회 시점의 스냅샷이라, 회차가 도는
 * 동안(대상이 많으면 길다) 광고 수신을 철회하거나 정지·탈퇴된 회원에게 그대로 광고가 나간다. 광고성 알림은
 * 철회가 다음 발송부터 즉시 반영돼야 하므로(정책 KNK-1129), 회원마다 다시 읽어 상태와 동의를 재확인한다.
 * 재확인과 발송 사이에도 밀리초 창은 남지만, **회차 길이만 한 창을 밀리초로 좁히는 것**이 여기서 할 수 있는
 * 최선이다(발송은 외부 IO라 트랜잭션으로 묶을 수 없다).
 *
 * 야간(21~08시 KST) 제한도 같은 자리에서 [com.knk.manyak.auth.entity.User.canReceiveMarketingPush]로 본다:
 * 발송 시각이 09:00이라 지금은 사실상 항상 통과하지만, 시각을 옮겨도 코드가 그대로이도록 판정을 둔다.
 *
 * 트랜잭션을 열지 않는다. 대상 조회는 Spring Data가 여는 짧은 읽기 트랜잭션 하나로 끝나고, **발송은 그
 * 밖에서** 돈다 — 외부 IO(FCM)를 트랜잭션 안에 두면 회차 내내 커넥션을 쥔 채 네트워크를 기다린다.
 */
@Service
class AttendanceReminderService(
    private val userRepository: UserRepository,
    private val pushMessageTemplateService: PushMessageTemplateService,
    private val fcmPushSender: FcmPushSender,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendReminders(): AttendanceReminderResult {
        val now = clock.instant()
        val today = LocalDate.now(clock.withZone(SEOUL_ZONE))
        val targetIds = userRepository.findAttendanceReminderTargetIds(today.toString())
        if (targetIds.isEmpty()) {
            return AttendanceReminderResult(targets = 0, sent = 0, skipped = 0)
        }

        // 문구는 회차 시작에 한 번만 해석한다. 회원마다 읽으면 갱신이 중간에 끼어들어 같은 회차가 두 문구로 나간다.
        val text = pushMessageTemplateService.templateOf(PushTemplateKey.ATTENDANCE_REMINDER)
        val data = mapOf(
            "type" to TYPE_ATTENDANCE_REMINDER,
            "date" to today.toString(),
            // (광고) 표기는 서버가 붙인다(DB 값에 맡기지 않는다).
            "title" to withAdPrefix(text.title),
            "body" to text.body,
        )

        var sent = 0
        var skipped = 0
        targetIds.forEach { userId ->
            // 발송 직전 재조회. 조회 시점의 스냅샷을 믿으면 회차 도중의 철회·정지·탈퇴가 반영되지 않는다.
            val user = userRepository.findById(userId).orElse(null)
            if (user == null || user.status != UserStatus.ACTIVE || !user.canReceiveMarketingPush(now)) {
                skipped++
                return@forEach
            }
            // 한 회원의 발송 실패가 나머지 회차를 끊지 않는다. 개별 토큰 실패는 FcmPushSender가 이미 흡수한다.
            runCatching { fcmPushSender.sendToUser(userId, data) }
                .onSuccess { sent++ }
                .onFailure { logger.warn("출석 리마인드 발송에 실패했습니다. (userId={}, error={})", userId, it.javaClass.simpleName) }
        }
        return AttendanceReminderResult(targets = targetIds.size, sent = sent, skipped = skipped)
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** 앱이 알림 UI를 조립할 때 쓰는 시나리오 식별자(data 전용 메시지 — KNK-1130). */
        const val TYPE_ATTENDANCE_REMINDER = "ATTENDANCE_REMINDER"
    }
}
