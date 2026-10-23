package com.knk.manyak.admin.infra

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class InfraTableSize(val schema: String, val table: String, val totalBytes: Long, val tableBytes: Long,
    val indexBytes: Long, val estimatedRows: Long)

@Repository
class InfraDatabaseRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun largestTables(limit: Int = 20): List<InfraTableSize> {
        require(limit in 1..100)
        return jdbc.query("""
            SELECT n.nspname AS schema_name, c.relname AS table_name,
                pg_total_relation_size(c.oid) AS total_bytes,
                pg_table_size(c.oid) AS table_bytes, pg_indexes_size(c.oid) AS index_bytes,
                GREATEST(c.reltuples, 0)::bigint AS estimated_rows
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE c.relkind='r' AND n.nspname <> 'information_schema' AND n.nspname NOT LIKE 'pg_%'
            ORDER BY total_bytes DESC, n.nspname ASC, c.relname ASC LIMIT :limit
        """.trimIndent(), mapOf("limit" to limit)) { rs, _ ->
            InfraTableSize(rs.getString("schema_name"), rs.getString("table_name"), rs.getLong("total_bytes"),
                rs.getLong("table_bytes"), rs.getLong("index_bytes"), rs.getLong("estimated_rows"))
        }
    }
}
