package com.knk.manyak.push.service

import com.google.firebase.messaging.AndroidConfig.Priority.HIGH
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.repository.CreditLotRepository
import com.knk.manyak.push.inbox.ScheduledNotificationWriter
import com.knk.manyak.push.outbox.ScheduledPushOutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

data class CreditExpiryReminderResult(val targets: Int, val cooldown: Int, val sent: Int)

/** 만료 창의 회원을 조회하고 회차 커밋 뒤 알림 내역 기준의 쿨다운을 기록한다. */
@Service
class CreditExpiryReminderService(
    private val lots: CreditLotRepository,
    private val users: UserRepository,
    private val templates: PushMessageTemplateService,
    private val sender: FcmPushSender,
    private val redis: StringRedisTemplate,
    private val notifications: ScheduledNotificationWriter,
    private val tokens: com.knk.manyak.push.repository.DevicePushTokenRepository,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${manyak.push.web-base-url:https://manyak.app}")
    private val webBaseUrl: String = "https://manyak.app",
    private val outbox: ScheduledPushOutboxWriter? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendReminders(): CreditExpiryReminderResult {
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul")).toString()
        val until = now.plus(Duration.ofHours(24))
        val candidates = lots.findExpiryReminderTargets(now, until)
        // 조회 실패는 기록 전에 전파해 부분 요청이 남지 않게 한다.
        val cooling = candidates.filter { redis.hasKey(cooldownKey(it.userId)) }.map { it.userId }.toSet()
        val targets = candidates.filter { it.userId !in cooling }
        val text = templates.templateOf(PushTemplateKey.CREDIT_EXPIRY_REMINDER)
        val data = mapOf("type" to TYPE, "title" to text.title, "body" to text.body,
            "deepLink" to pushDeepLink(webBaseUrl, "/my/credits?tab=free", TYPE))
        val round = notifications.recordCreditExpiry(targets, targets, data, today, outbox)
        // 모드·푸시 자격과 관계없이 커밋된 알림 내역 대상 전체에 쿨다운을 건다.
        targets.forEach { redis.opsForValue().set(cooldownKey(it.userId), "1", Duration.ofDays(7)) }
        var sent = 0
        if (round.recordedPushes != null) {
            sent = round.recordedPushes
        } else {
            for (target in targets) {
                val user = users.findById(target.userId).orElse(null)
                val messageId = "credit-expiry:${user?.publicId}:$today"
                val rejection = when {
                    user == null || user.status != UserStatus.ACTIVE -> "NOT_ACTIVE"
                    !user.servicePushEnabled -> "SERVICE_PUSH_DISABLED"
                    tokens.findTop10ByUserIdOrderByUpdatedAtDesc(user.id).isEmpty() -> "NO_TOKENS"
                    !clock.instant().isBefore(target.expiresAt) -> "EXPIRED"
                    else -> null
                }
                if (rejection != null) {
                    sender.recordDiscarded(target.userId, data, messageId, rejection)
                    continue
                }
                sent++
                runCatching {
                    // 기기별 만료 검사와 TTL 재계산은 기존 발송기가 맡는다.
                    sender.sendToUser(target.userId, data, HIGH, expiresAt = target.expiresAt,
                        messageId = messageId)
                }.onFailure {
                    logger.warn("이프 만료 예정 안내 발송 실패 (error={})", it.javaClass.simpleName)
                }
            }
        }
        return CreditExpiryReminderResult(candidates.size, candidates.count { it.userId in cooling }, sent)
    }

    private fun cooldownKey(userId: Long) = "push:credit-expiry-reminder:cooldown:$userId"
    private companion object { const val TYPE = "CREDIT_EXPIRY_REMINDER" }
}
