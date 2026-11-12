package com.knk.manyak.admin.analytics

import com.knk.manyak.adminkit.query.AdminDateRange
import com.knk.manyak.adminkit.query.badAdminQuery
import java.time.*

open class AnalyticsRangeQuery {
    var from: String? = null
    var to: String? = null
    var days: String? = null
    fun range(clock: Clock, maximum: Int = 366, default: Int = 30, allowed: Set<Int> = setOf(7, 30), endOffset: Long = 0, confirmedMaximum: Int = maximum): AnalyticsRange {
        val today = clock.instant().atZone(AdminDateRange.KST).toLocalDate()
        val last = today.plusDays(endOffset)
        if ((from == null) != (to == null) || (days != null && from != null)) badAdminQuery("from과 to는 함께 지정하고 days와 함께 사용할 수 없습니다.")
        if (from != null) {
            AdminDateRange.of(from, to)
            val value = AnalyticsRange(LocalDate.parse(from), LocalDate.parse(to))
            val limit = if (value.to <= today.minusDays(2)) confirmedMaximum else maximum
            if (value.to > last || value.days !in 1..limit) badAdminQuery("기간 범위가 올바르지 않습니다.")
            return value
        }
        val count = if (days == null) default else days!!.toIntOrNull() ?: badAdminQuery("days가 올바르지 않습니다.")
        if (count !in allowed || count > maximum) badAdminQuery("days가 올바르지 않습니다.")
        return AnalyticsRange(last.minusDays(count.toLong() - 1), last)
    }
}
