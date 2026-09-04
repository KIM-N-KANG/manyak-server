package com.knk.manyak.push.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 한 회차의 발송 결과. [targets]는 조회된 대상 수, [sent]는 실제로 발송기를 부른 수(야간 제한 등으로 줄어들 수 있다). */
data class AttendanceReminderResult(val targets: Int, val sent: Int)

/**
 * 출석 리마인드 푸시(KNK-1116). **광고성 알림**이라 사전 동의가 필수다(정보통신망법 제50조, 정책 KNK-1129).
 *
 * 대상은 `ACTIVE` ∩ 광고 동의 ∩ 등록 토큰 보유 ∩ 당일(KST) 출석 미수령이며, 판정은 전부
 * [UserRepository.findAttendanceReminderTargets] 한 쿼리가 한다(새 상태 컬럼 없음 — 원장의 멱등 키 부재).
 * 야간(21~08시 KST) 제한만 회원별로 [User.canReceiveMarketingPush]로 다시 본다: 발송 시각이 09:00이라
 * 지금은 사실상 항상 통과하지만, 시각을 옮겨도 코드가 그대로이도록 판정을 둔다.
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
        val targets = userRepository.findAttendanceReminderTargets(today.toString())
        if (targets.isEmpty()) {
            return AttendanceReminderResult(targets = 0, sent = 0)
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
        targets.forEach { user ->
            if (!user.canReceiveMarketingPush(now)) {
                return@forEach
            }
            // 한 회원의 발송 실패가 나머지 회차를 끊지 않는다. 개별 토큰 실패는 FcmPushSender가 이미 흡수한다.
            runCatching { fcmPushSender.sendToUser(user.id, data) }
                .onSuccess { sent++ }
                .onFailure { logger.warn("출석 리마인드 발송에 실패했습니다. (userId={}, error={})", user.id, it.javaClass.simpleName) }
        }
        return AttendanceReminderResult(targets = targets.size, sent = sent)
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** 앱이 알림 UI를 조립할 때 쓰는 시나리오 식별자(data 전용 메시지 — KNK-1130). */
        const val TYPE_ATTENDANCE_REMINDER = "ATTENDANCE_REMINDER"
    }
}
