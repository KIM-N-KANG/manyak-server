package com.knk.manyak.push.delivery

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.repository.PushDeliveryRepository
import com.knk.manyak.push.service.PushDeliveryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PushDeliveryServiceTests {
    @Test fun `기본 통계 기간은 UTC 자정이 아닌 KST 오늘까지 7일이다`() {
        val repository = mock(PushDeliveryRepository::class.java)
        val service = PushDeliveryService(repository, mock(UserRepository::class.java),
            Clock.fixed(Instant.parse("2026-10-20T15:00:00Z"), ZoneOffset.UTC))
        val result = service.stats(null, null, null)
        assertThat(result.from).isEqualTo(LocalDate.parse("2026-10-15"))
        assertThat(result.to).isEqualTo(LocalDate.parse("2026-10-21"))
        verify(repository).dailyStats(result.from, result.to, null)
    }
}
