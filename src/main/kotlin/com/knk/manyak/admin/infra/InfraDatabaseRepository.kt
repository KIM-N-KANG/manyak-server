package com.knk.manyak.admin.infra

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class InfraDatabaseRepository(private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional(readOnly = true)
    fun snapshot(limit: Int): InfraDatabaseSnapshot {
        require(limit in 1..100)
        val filter = "c.relkind='r' AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast')"
        val summary = jdbc.queryForMap("SELECT pg_database_size(current_database()) AS bytes, COUNT(*) AS n FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE $filter", emptyMap<String, Any>())
        val rows = jdbc.query("""
            SELECT n.nspname AS schema_name, c.relname AS table_name,
                pg_total_relation_size(c.oid) AS total_bytes, pg_relation_size(c.oid) AS table_bytes,
                pg_indexes_size(c.oid) AS index_bytes, COALESCE(s.n_live_tup,0) AS estimated_rows
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            LEFT JOIN pg_stat_user_tables s ON s.relid=c.oid WHERE $filter
            ORDER BY total_bytes DESC, n.nspname ASC, c.relname ASC LIMIT :limit
        """.trimIndent(), mapOf("limit" to limit)) { rs, _ ->
            InfraTableSize(rs.getString("schema_name"), rs.getString("table_name"), rs.getLong("total_bytes"), rs.getLong("table_bytes"), rs.getLong("index_bytes"), rs.getLong("estimated_rows")) }
        return InfraDatabaseSnapshot((summary["bytes"] as Number).toLong(), (summary["n"] as Number).toLong(), rows)
    }
}
