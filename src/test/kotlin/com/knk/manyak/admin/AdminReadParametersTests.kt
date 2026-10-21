package com.knk.manyak.admin

import com.knk.manyak.admin.config.AdminDashboardConfig
import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminDateRange
import com.knk.manyak.admin.dto.AdminPagination
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Instant

class AdminReadParametersTests {
    @Test fun `팀 계정 목록은 기본값과 빈 값과 사용자 설정을 바인딩하며 잘못된 값은 기동에 실패한다`() {
        val context = ApplicationContextRunner().withUserConfiguration(AdminDashboardConfig::class.java)
        context.run { assertThat(it.getBean(AdminDashboardProperties::class.java).excludedUserIds).containsExactly(1, 2, 3, 4, 5, 10, 11, 206, 207) }
        context.withPropertyValues("manyak.admin.dashboard.excluded-user-ids=").run {
            assertThat(it.getBean(AdminDashboardProperties::class.java).excludedUserIds).isEmpty()
        }
        context.withPropertyValues("manyak.admin.dashboard.excluded-user-ids=12,34").run {
            assertThat(it.getBean(AdminDashboardProperties::class.java).excludedUserIds).containsExactly(12, 34)
        }
        context.withPropertyValues("manyak.admin.dashboard.excluded-user-ids=not-an-integer").run { assertThat(it).hasFailed() }
    }

    @Test fun `KST 양끝 날짜는 UTC 반개구간으로 변환한다`() {
        val range = AdminDateRange.of("2026-10-21", "2026-10-21")
        assertThat(range.from).isEqualTo(Instant.parse("2026-10-20T15:00:00Z"))
        assertThat(range.until).isEqualTo(Instant.parse("2026-10-21T15:00:00Z"))
        assertThat(AdminDateRange.of(null, null).from).isNull()
        assertThat(AdminDateRange.of(null, null).until).isNull()
        for (date in listOf("2026-02-30", "2026-13-01", "not-a-date")) {
            assertThatThrownBy { AdminDateRange.of(date, null) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        }
    }

    @Test fun `큰 페이지 번호의 offset은 정수 overflow 없이 계산한다`() {
        val page = AdminPagination(Int.MAX_VALUE, 100, "created_at desc")
        assertThat(page.offset).isEqualTo(214748364600L)
        assertThat(page.response(emptyList<Any>(), 101).totalPages).isEqualTo(2)
        assertThat(page.response(emptyList<Any>(), 0).totalPages).isZero()
    }
}
