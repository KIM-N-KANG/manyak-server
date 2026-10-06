package com.knk.manyak.global.config

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import net.ttddyy.observation.tracing.QueryContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdbcQuerySpanNamingTests {
    private val filter = JdbcQuerySpanNaming().jdbcQueryNameFilter()

    @Test
    fun `동작과 첫 테이블만 스팬 이름에 남긴다`() {
        mapOf(
            "select ss1_0.id from story_settings ss1_0 where ss1_0.id=?" to "SELECT story_settings",
            "INSERT INTO story_messages (content) values (?)" to "INSERT story_messages",
            "update story_messages sm set content=? where sm.id=?" to "UPDATE story_messages",
            "delete from story_messages sm where sm.id=?" to "DELETE story_messages",
            "select count(s.id) from public.stories s" to "SELECT public.stories",
            "select 'from fake_table' from stories s" to "SELECT stories",
        ).forEach { (sql, expected) ->
            assertThat(apply(sql).contextualName).describedAs(sql).isEqualTo(expected)
        }
    }

    @Test
    fun `일반 문자열 끝의 e는 E-string 접두사로 보지 않는다`() {
        listOf(
            "select 'from fake_table' from stories s",
            "select 'NAME' from stories",
            "select '1e' from stories",
            "select '_e' from stories",
        ).forEach { sql ->
            assertThat(apply(sql).contextualName).describedAs(sql).isEqualTo("SELECT stories")
        }
    }

    @Test
    fun `인용 식별자와 주석이 있는 SQL은 내용을 해석하지 않는다`() {
        listOf(
            "SELECT 1 AS \"FROM synthetic_marker42 \" FROM stories",
            "SELECT /* outer /* inner */ FROM synthetic_marker73 */ id FROM stories",
            "SELECT -- FROM synthetic_marker84\n id FROM stories",
            "/* Hibernate */ select count(s.id) from public.stories s",
            "select \"name'\" from stories",
        ).forEach { sql ->
            val context = apply(sql)
            assertThat(context.contextualName).describedAs(sql).isEqualTo("query")
            assertThat(context.name).isEqualTo("jdbc.query")
            assertThat(context.queries).containsExactly(sql)
        }
    }

    @Test
    fun `CTE와 복잡하거나 알 수 없는 SQL은 기존 이름을 유지한다`() {
        listOf(
            "with recent as (select * from stories) select * from recent",
            "select 1", "select * from (select * from stories) s",
            "select (select id from stories) from story_messages", "not sql", "",
        ).forEach { assertThat(apply(it).contextualName).isEqualTo("query") }
    }

    @Test
    fun `PostgreSQL 특수 리터럴 안의 FROM과 숫자는 스팬 이름에 복사하지 않는다`() {
        listOf(
            "SELECT \$\$FROM synthetic_secret42 \$\$ FROM stories",
            "SELECT \$tag\$FROM synthetic_secret73 \$tag\$ FROM stories",
            "SELECT E'can\\'t FROM synthetic_secret91 ' FROM stories",
            "SELECT e'can\\'t FROM synthetic_secret92 ' FROM stories",
        ).forEach { sql ->
            val context = apply(sql)
            assertThat(context.contextualName).describedAs(sql).isEqualTo("query")
            assertThat(context.name).isEqualTo("jdbc.query")
            assertThat(context.queries).containsExactly(sql)
        }
    }

    @Test
    fun `관측 이름 속성과 부모를 보존하여 루트 필터가 계속 동작한다`() {
        val registry = ObservationRegistry.create().apply { observationConfig().observationHandler { true } }
        val parent = Observation.start("http.server.requests", registry)
        try {
            val context = QueryContext().apply {
                setName("jdbc.query")
                contextualName = "query"
                queries.add("select * from stories where id=?")
                parentObservation = parent
            }
            filter.map(context)
            assertThat(context.name).isEqualTo("jdbc.query")
            assertThat(context.queries).containsExactly("select * from stories where id=?")
            assertThat(context.parentObservation).isSameAs(parent)
            context.parentObservation = Observation.NOOP
            assertThat(TracingNoiseFilter.predicate { parent }.test(requireNotNull(context.name), context)).isTrue()
            assertThat(context.parentObservation).isSameAs(parent)
            context.parentObservation = Observation.NOOP
            assertThat(TracingNoiseFilter.predicate { Observation.NOOP }.test(requireNotNull(context.name), context)).isFalse()
            context.parentObservation = parent
            assertThat(TracingNoiseFilter.predicate().test(requireNotNull(context.name), context)).isTrue()
            context.parentObservation = null
            assertThat(TracingNoiseFilter.predicate().test(requireNotNull(context.name), context)).isFalse()
            val other = Observation.Context().apply { setName("http.server.requests"); contextualName = "original" }
            assertThat(filter.map(other).contextualName).isEqualTo("original")
        } finally {
            parent.stop()
        }
    }

    private fun apply(sql: String): QueryContext = QueryContext().apply {
        setName("jdbc.query")
        contextualName = "query"
        queries.add(sql)
        filter.map(this)
    }
}
