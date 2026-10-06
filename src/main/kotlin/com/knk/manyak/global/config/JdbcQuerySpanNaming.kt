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
        // PostgreSQL dollar quoting과 E-string은 단순 정규식으로 해석하지 않는다.
        // 리터럴 내용이 이름에 들어가지 않도록 원문에서 먼저 보수적으로 제외한다.
        if ('$' in sql || escapeStringStart.containsMatchIn(sql)) return null
        val normalized = literalsAndComments.replace(sql, " ").trim()
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
        // 식별자 문자 뒤의 e'는 E-string 접두사가 아니다(예: 'fake_table').
        val escapeStringStart = Regex("""(?<![A-Za-z0-9_])[Ee]'""")
        val literalsAndComments = Regex("""'(?:''|[^'])*'|/\*.*?\*/|--[^\r\n]*""", RegexOption.DOT_MATCHES_ALL)
        val firstWord = Regex("""^[A-Za-z]+\b""")
        val selectWord = Regex("""\bSELECT\b""", RegexOption.IGNORE_CASE)
        const val TABLE = "([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)?)(?=\\s|[,;(]|$)"
        val fromTable = Regex("""\bFROM\s+$TABLE""", RegexOption.IGNORE_CASE)
        val insertTable = Regex("""^INSERT\s+INTO\s+$TABLE""", RegexOption.IGNORE_CASE)
        val updateTable = Regex("""^UPDATE\s+$TABLE""", RegexOption.IGNORE_CASE)
        val deleteTable = Regex("""^DELETE\s+FROM\s+$TABLE""", RegexOption.IGNORE_CASE)
    }
}
