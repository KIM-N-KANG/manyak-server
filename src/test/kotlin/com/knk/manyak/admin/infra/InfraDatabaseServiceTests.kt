package com.knk.manyak.admin.infra

import com.knk.manyak.admin.analytics.MutableAnalyticsClock
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.*

class InfraDatabaseServiceTests {
    @Test fun `테이블 캐시는 limit마다 5분이며 만료 뒤 DB 오류는 이전 값으로 숨기지 않는다`() {
        val repository = mock(InfraDatabaseRepository::class.java)
        val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T00:00:00Z"))
        val service = InfraDatabaseService(repository, InfraMetricsProperties(), clock)
        `when`(repository.snapshot(anyInt())).thenReturn(InfraDatabaseSnapshot(1000, 3, listOf(
            InfraTableSize("public", "z", 200, 100, 50, 1), InfraTableSize("public", "a", 300, 200, 50, 2), InfraTableSize("public", "b", 200, 150, 20, 3))))
        val first = service.tables(2)
        assertThat(first.tables.map { it.name }).containsExactly("a", "b"); assertThat(first.tableCount).isEqualTo(3)
        clock.at = clock.at.plusSeconds(299); assertThat(service.tables(2)).isSameAs(first)
        service.tables(1); verify(repository).snapshot(1)
        clock.at = clock.at.plusSeconds(1)
        `when`(repository.snapshot(2)).thenThrow(IllegalStateException("DB failed"))
        assertThatThrownBy { service.tables(2) }.isInstanceOf(IllegalStateException::class.java)
    }
}
