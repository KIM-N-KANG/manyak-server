package com.knk.manyak.auth.entity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 광고 푸시 수신 판정(KNK-1132, 정책 KNK-1129). 야간(21~08시 KST)에는 야간 동의가 따로 있어야 한다.
 * 발송기(KNK-1116·1117)가 이 판정을 쓴다.
 */
class UserMarketingPushConsentTest {

    private fun userWith(marketing: Instant?, night: Instant?): User =
        User(
            nickname = "수신자",
            marketingPushAgreedAt = marketing,
            marketingPushNightAgreedAt = night,
        )

    /** [hour]시 정각(KST)의 Instant. */
    private fun kstAt(hour: Int): Instant =
        LocalDate.of(2026, 9, 4).atTime(LocalTime.of(hour, 0)).atZone(ZoneId.of("Asia/Seoul")).toInstant()

    private val agreed = Instant.parse("2026-09-01T00:00:00Z")

    @Test
    fun `주간에는 광고 동의만 있으면 받는다`() {
        assertThat(userWith(agreed, null).canReceiveMarketingPush(kstAt(14))).isTrue()
    }

    @Test
    fun `광고 동의가 없으면 주간에도 받지 않는다`() {
        assertThat(userWith(null, agreed).canReceiveMarketingPush(kstAt(14))).isFalse()
    }

    @Test
    fun `야간에는 야간 동의가 없으면 받지 않는다`() {
        // 21시와 익일 07시 모두 야간이다(21~08시 KST).
        assertThat(userWith(agreed, null).canReceiveMarketingPush(kstAt(21))).isFalse()
        assertThat(userWith(agreed, null).canReceiveMarketingPush(kstAt(7))).isFalse()
    }

    @Test
    fun `야간 동의가 있으면 야간에도 받는다`() {
        assertThat(userWith(agreed, agreed).canReceiveMarketingPush(kstAt(21))).isTrue()
        assertThat(userWith(agreed, agreed).canReceiveMarketingPush(kstAt(7))).isTrue()
    }
}
