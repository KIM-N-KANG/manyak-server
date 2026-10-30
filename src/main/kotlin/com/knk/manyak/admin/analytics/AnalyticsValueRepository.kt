package com.knk.manyak.admin.analytics

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.*

/** 정의에는 날짜와 비밀값을 넣지 않는다. 이벤트·필터의 JSON 객체 키도 정렬해 해시한다. */
class AnalyticsDefinition(val metric: String, val path: String, val parameters: List<Pair<String, String>>, val perDay: Int, mapper: ObjectMapper,
    hashParameters: List<Pair<String, String>> = emptyList()) {
    val params: String
    val hash: String
    init {
        fun ordered(node: JsonNode): Any? = when {
            node.isObject -> node.properties().associate { it.key to ordered(it.value) }.toSortedMap()
            node.isArray -> node.toList().map(::ordered)
            node.isNull -> null
            else -> node
        }
        val query = parameters.sortedBy { it.first }.map { (key, value) -> listOf(key, if (key in setOf("e", "e2", "se", "re", "s", "rb")) ordered(mapper.readTree(value)) else value) }
        params = mapper.writeValueAsString(sortedMapOf("path" to path, "query" to query))
        // 원본 식별에만 필요한 공개 키 등은 params에 저장하지 않는다. 기존 분석 정의의 해시는 유지한다.
        val hashInput = if (hashParameters.isEmpty()) params else mapper.writeValueAsString(listOf(params, hashParameters.sortedBy { it.first }.map { listOf(it.first, it.second) }))
        hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(hashInput.toByteArray(Charsets.UTF_8)))
    }
}
data class AnalyticsValue(val value: JsonNode, val fetchedAt: Instant, val finalizedAt: Instant? = null)

@Repository
class AnalyticsValueRepository(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper, manager: PlatformTransactionManager) {
    private val transaction = TransactionTemplate(manager)
    private val postgres = jdbc.jdbcTemplate.dataSource!!.connection.use { it.metaData.databaseProductName == "PostgreSQL" }
    fun daily(def: AnalyticsDefinition, from: LocalDate, to: LocalDate): Map<LocalDate, AnalyticsValue> =
        jdbc.query("SELECT metric_date, \"value\", fetched_at, finalized_at FROM admin_analytics_daily_values WHERE metric=:metric AND params_hash=:hash AND metric_date BETWEEN :from AND :to", key(def) + mapOf("from" to from, "to" to to)) { rs, _ ->
            rs.getObject("metric_date", LocalDate::class.java) to value(rs)
        }.toMap()
    fun range(def: AnalyticsDefinition, from: LocalDate, to: LocalDate): AnalyticsValue? =
        jdbc.query("SELECT \"value\", fetched_at, finalized_at FROM admin_analytics_range_values WHERE metric=:metric AND params_hash=:hash AND range_from=:from AND range_to=:to", key(def) + mapOf("from" to from, "to" to to)) { rs, _ -> value(rs) }.firstOrNull()
    fun saveDaily(def: AnalyticsDefinition, values: Map<LocalDate, AnalyticsValue>) {
        transaction.executeWithoutResult { values.forEach { (date, value) -> upsert("daily", def, mapOf("metric_date" to date), value) } }
    }
    fun saveRange(def: AnalyticsDefinition, from: LocalDate, to: LocalDate, value: AnalyticsValue) {
        transaction.executeWithoutResult { upsert("range", def, mapOf("range_from" to from, "range_to" to to), value) }
    }
    fun finalizeDaily(def: AnalyticsDefinition, date: LocalDate, value: AnalyticsValue, now: Instant) {
        jdbc.update("UPDATE admin_analytics_daily_values SET finalized_at=:now WHERE metric=:metric AND params_hash=:hash AND metric_date=:date AND finalized_at IS NULL AND fetched_at=:fetched", key(def) + mapOf("date" to date, "now" to now.atOffset(ZoneOffset.UTC), "fetched" to value.fetchedAt.atOffset(ZoneOffset.UTC)))
    }
    fun unfinalizeDaily(def: AnalyticsDefinition, date: LocalDate, value: AnalyticsValue) {
        jdbc.update("UPDATE admin_analytics_daily_values SET finalized_at=NULL WHERE metric=:metric AND params_hash=:hash AND metric_date=:date AND fetched_at=:fetched AND finalized_at=:finalized", key(def) + mapOf("date" to date, "fetched" to value.fetchedAt.atOffset(ZoneOffset.UTC), "finalized" to value.finalizedAt?.atOffset(ZoneOffset.UTC)))
    }
    fun finalizeRange(def: AnalyticsDefinition, from: LocalDate, to: LocalDate, value: AnalyticsValue, now: Instant) {
        jdbc.update("UPDATE admin_analytics_range_values SET finalized_at=:now WHERE metric=:metric AND params_hash=:hash AND range_from=:from AND range_to=:to AND finalized_at IS NULL AND fetched_at=:fetched", key(def) + mapOf("from" to from, "to" to to, "now" to now.atOffset(ZoneOffset.UTC), "fetched" to value.fetchedAt.atOffset(ZoneOffset.UTC)))
    }
    fun unfinalizeRange(def: AnalyticsDefinition, from: LocalDate, to: LocalDate, value: AnalyticsValue) {
        jdbc.update("UPDATE admin_analytics_range_values SET finalized_at=NULL WHERE metric=:metric AND params_hash=:hash AND range_from=:from AND range_to=:to AND fetched_at=:fetched AND finalized_at=:finalized", key(def) + mapOf("from" to from, "to" to to, "fetched" to value.fetchedAt.atOffset(ZoneOffset.UTC), "finalized" to value.finalizedAt?.atOffset(ZoneOffset.UTC)))
    }
    private fun key(def: AnalyticsDefinition) = mapOf("metric" to def.metric, "hash" to def.hash)
    private fun value(rs: java.sql.ResultSet) = AnalyticsValue(mapper.readTree(rs.getString("value")), rs.getTimestamp("fetched_at").toInstant(), rs.getTimestamp("finalized_at")?.toInstant())
    private fun upsert(kind: String, def: AnalyticsDefinition, dates: Map<String, LocalDate>, value: AnalyticsValue) {
        val table = "admin_analytics_${kind}_values"
        val columns = listOf("metric", "params_hash", "params") + dates.keys + listOf("\"value\"", "fetched_at", "finalized_at")
        val keyColumns = listOf("metric", "params_hash") + dates.keys
        val binding = listOf(":metric", ":hash", if (postgres) "CAST(:params AS jsonb)" else ":params FORMAT JSON") + dates.keys.map { ":$it" } +
            listOf(if (postgres) "CAST(:value AS jsonb)" else ":value FORMAT JSON", ":fetched", ":finalized")
        val sql = if (postgres) """INSERT INTO $table (${columns.joinToString()}) VALUES (${binding.joinToString()})
            ON CONFLICT (${keyColumns.joinToString()}) DO UPDATE SET "value"=EXCLUDED."value", fetched_at=EXCLUDED.fetched_at, finalized_at=EXCLUDED.finalized_at
            WHERE $table.finalized_at IS NULL""" else """MERGE INTO $table t USING (VALUES (${binding.joinToString()})) s (${columns.joinToString()})
            ON (${keyColumns.joinToString(" AND ") { "t.$it=s.$it" }})
            WHEN MATCHED AND t.finalized_at IS NULL THEN UPDATE SET t."value"=s."value", t.fetched_at=s.fetched_at, t.finalized_at=s.finalized_at
            WHEN NOT MATCHED THEN INSERT (${columns.joinToString()}) VALUES (${columns.joinToString { "s.$it" }})"""
        jdbc.update(sql, key(def) + dates + mapOf("params" to def.params, "value" to mapper.writeValueAsString(value.value), "fetched" to value.fetchedAt.atOffset(ZoneOffset.UTC), "finalized" to value.finalizedAt?.atOffset(ZoneOffset.UTC)))
    }
}
