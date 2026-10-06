package com.knk.manyak.global.config

import io.micrometer.observation.ObservationFilter
import net.ttddyy.observation.tracing.QueryContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Locale

@Configuration(proxyBeanMethods = false)
class JdbcQuerySpanNaming {
    @Bean
    fun jdbcQueryNameFilter(): ObservationFilter = ObservationFilter { context ->
        if (context is QueryContext && context.name == "jdbc.query") {
            summary(context.queries.firstOrNull())?.let { context.contextualName = it }
        }
        // 관측 이름, SQL 속성과 부모는 보존한다. 루트 JDBC 필터도 같은 이름을 사용한다.
        context
    }

    private fun summary(sql: String?): String? {
        if (sql == null || sql.length > 4096) return null
        // 일반 작은따옴표 문자열과 비인용 식별자로 된 단순 SQL만 요약한다.
        // 인용 식별자·특수 문자열·주석이 있으면 내용을 해석하지 않고 기존 이름을 유지한다.
        if (unsupportedSyntax.containsMatchIn(sql)) return null
        val normalized = stringLiterals.replace(sql, " ").trim()
        val operation = firstWord.find(normalized)?.value?.uppercase(Locale.ROOT) ?: return null
        // CTE·중첩 SELECT 등은 단순 규칙으로 테이블을 추측하지 않는다.
        val table = when (operation) {
            "SELECT" -> {
                if (selectWord.findAll(normalized).count() != 1) return null
                fromTable.find(normalized)?.groupValues?.get(1)
            }
            "INSERT" -> insertTable.find(normalized)?.groupValues?.get(1)
            "UPDATE" -> updateTable.find(normalized)?.groupValues?.get(1)
            "DELETE" -> deleteTable.find(normalized)?.groupValues?.get(1)
            else -> null
        } ?: return null
        return "$operation $table"
    }

    private companion object {
        val unsupportedSyntax = Regex("""["$]|/\*|--|(?<![A-Za-z0-9_])[Ee]'""")
        val stringLiterals = Regex("""'(?:''|[^'])*'""")
        val firstWord = Regex("""^[A-Za-z]+\b""")
        val selectWord = Regex("""\bSELECT\b""", RegexOption.IGNORE_CASE)
        const val TABLE = "([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)(?=\\s|[,;(]|$)"
        val fromTable = Regex("""\bFROM\s+$TABLE""", RegexOption.IGNORE_CASE)
        val insertTable = Regex("""^INSERT\s+INTO\s+$TABLE""", RegexOption.IGNORE_CASE)
        val updateTable = Regex("""^UPDATE\s+$TABLE""", RegexOption.IGNORE_CASE)
        val deleteTable = Regex("""^DELETE\s+FROM\s+$TABLE""", RegexOption.IGNORE_CASE)
    }
}
