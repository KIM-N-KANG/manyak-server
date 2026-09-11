package com.knk.manyak.push.service

import com.knk.manyak.push.entity.PushMessageTemplate
import com.knk.manyak.push.repository.PushMessageTemplateRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** 런타임 조정 가능한 푸시 문구의 키(KNK-1116). [storageKey]가 `push_message_templates.template_key`와 짝이다. */
enum class PushTemplateKey(val storageKey: String) {
    ATTENDANCE_REMINDER("attendance_reminder"),
}

/** 해석된 알림 문구. 오버라이드 행 또는 yml 기본값에서 온다. */
data class PushMessageText(val title: String, val body: String)

/**
 * 정보통신망법 제50조의 광고성 표기. **DB 값에 맡기지 않고 발송 시점에 서버가 붙인다** — 운영 SQL로 문구를
 * 바꾸다 빠뜨리면 표기 없는 광고가 나가고, 그건 되돌릴 수 없는 발송이다. 운영자가 이미 붙여 둔 경우까지
 * 고려해 중복 접두는 만들지 않는다.
 */
fun withAdPrefix(title: String): String =
    if (title.startsWith(AD_PREFIX)) title else "$AD_PREFIX $title"

private const val AD_PREFIX = "(광고)"

/**
 * 푸시 문구를 해석한다(KNK-1116): 유효한 오버라이드 행이 있으면 그 값, 없으면 yml 기본 문구.
 *
 * [com.knk.manyak.credit.service.CreditPolicyService]와 같은 규칙이다.
 * - **읽기는 순수 메모리 연산이다.** 적재는 부팅 1회([preloadOnStartup])와 주기 갱신
 *   ([com.knk.manyak.push.scheduler.PushTemplateRefreshScheduler])만 한다.
 * - 만료(`effective_until`)와 시작(`effective_from`)은 캐시가 아니라 **읽을 때** 판정하므로 갱신 주기를
 *   기다리지 않고 반영된다.
 * - `@Value`에 `:폴백`을 두지 않는다 — 폴백이 있으면 yml 키를 오타 내도 하드코딩 문구가 조용히 먹어 정본이
 *   둘이 된다. 키가 없으면 부팅이 실패하는 게 맞다.
 * - 적재 실패는 요청을 막지 않는다. warn을 남기고 직전 스냅샷을 유지하며, 스냅샷이 없으면 yml 기본 문구다.
 */
@Service
class PushMessageTemplateService(
    private val pushMessageTemplateRepository: PushMessageTemplateRepository,
    // yml 기본 문구. 오버라이드가 없을 때 쓰며, 정본은 application.yml이다.
    @param:Value("\${manyak.push.templates.attendance-reminder.title}") attendanceReminderTitle: String,
    @param:Value("\${manyak.push.templates.attendance-reminder.body}") attendanceReminderBody: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val defaults: Map<PushTemplateKey, PushMessageText> = PushTemplateKey.entries.associateWith { key ->
        when (key) {
            PushTemplateKey.ATTENDANCE_REMINDER -> PushMessageText(attendanceReminderTitle, attendanceReminderBody)
        }
    }

    // 갱신끼리만 직렬화한다(부팅 선적재 · 스케줄러). 읽기 경로는 이 락을 잡지 않는다.
    private val refreshLock = Any()

    @Volatile
    private var overrides: List<PushMessageTemplate> = emptyList()

    /** [key]의 현재 유효 문구. **DB를 조회하지 않는다**(메모리 스냅샷 읽기 + 기간 판정뿐). */
    fun templateOf(key: PushTemplateKey): PushMessageText {
        val now = clock.instant()
        return overrides
            .filter { it.templateKey == key.storageKey && it.isEffectiveAt(now) }
            // 유효한 행이 여럿이면 가장 최근에 시작된 것을 쓴다(예약 교체가 자연히 동작한다).
            .maxByOrNull { it.effectiveFrom }
            ?.let { PushMessageText(it.title, it.body) }
            ?: defaults.getValue(key)
    }

    /** 오버라이드 스냅샷을 다시 적재한다. **예외를 밖으로 내보내지 않는다**(스케줄러가 한 번의 예외로 멈추지 않도록). */
    fun refresh() {
        synchronized(refreshLock) {
            try {
                overrides = pushMessageTemplateRepository.findAll().filter { it.templateKey in knownKeys }
            } catch (exception: Exception) {
                // 문구 적재 실패로 발송을 막지 않는다. 직전 스냅샷(없으면 빈 목록 = yml 기본 문구)을 유지한다.
                logger.warn("push_template_load_failed: 직전 스냅샷을 유지한다", exception)
            }
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun preloadOnStartup() {
        refresh()
    }

    private fun PushMessageTemplate.isEffectiveAt(now: Instant): Boolean =
        !now.isBefore(effectiveFrom) && (effectiveUntil == null || now.isBefore(effectiveUntil))

    private val knownKeys = PushTemplateKey.entries.map { it.storageKey }.toSet()
}
