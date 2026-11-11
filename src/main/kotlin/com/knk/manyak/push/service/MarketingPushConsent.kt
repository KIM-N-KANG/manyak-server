package com.knk.manyak.push.service

import com.knk.manyak.auth.dto.PushRecipient
import java.time.Instant
import java.time.ZoneId

/**
 * [at] 시점에 광고 푸시를 보낼 수 있는지(KNK-1132, 정책 KNK-1129). 발송기(KNK-1116, 1117)가 쓴다.
 *
 * 광고 동의가 있어야 하고, 야간(21~08시 KST)이면 야간 동의까지 있어야 한다. 서비스 알림은 야간 제한이
 * 없고 필드를 그대로 보면 되므로 헬퍼를 두지 않는다.
 */
fun PushRecipient.canReceiveMarketingPush(at: Instant): Boolean {
    if (marketingPushAgreedAt == null) {
        return false
    }
    return !isMarketingNightHour(at) || marketingPushNightAgreedAt != null
}

private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
private const val NIGHT_START_HOUR = 21
private const val NIGHT_END_HOUR = 8

/** 야간 광고 제한 구간 [21:00, 08:00) KST. 자정을 넘기므로 OR로 판정한다. */
private fun isMarketingNightHour(at: Instant): Boolean {
    val hour = at.atZone(SEOUL_ZONE).hour
    return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
}
