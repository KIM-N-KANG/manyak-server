package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminDateRange
import com.knk.manyak.admin.dto.badAdminQuery
import java.time.*

open class AnalyticsRangeQuery {
    var from: String? = null
    var to: String? = null
    var days: String? = null
    fun range(clock: Clock, maximum: Int = 366, default: Int = 30, allowed: Set<Int> = setOf(7, 30), endOffset: Long = 0): AnalyticsRange {
        val last = clock.instant().atZone(AdminDateRange.KST).toLocalDate().plusDays(endOffset)
        if ((from == null) != (to == null) || (days != null && from != null)) badAdminQuery("from과 to는 함께 지정하고 days와 함께 사용할 수 없습니다.")
        if (from != null) {
            AdminDateRange.of(from, to)
            val value = AnalyticsRange(LocalDate.parse(from), LocalDate.parse(to))
            if (value.to > last || value.days !in 1..maximum) badAdminQuery("기간 범위가 올바르지 않습니다.")
            return value
        }
        val count = if (days == null) default else days!!.toIntOrNull() ?: badAdminQuery("days가 올바르지 않습니다.")
        if (count !in allowed || count > maximum) badAdminQuery("days가 올바르지 않습니다.")
        return AnalyticsRange(last.minusDays(count.toLong() - 1), last)
    }
}
