package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

class PlatformUnionTests {
    private val mapper = JsonMapper.builder().build()
    private val queries = AnalyticsQueryFactory(mapper)
    private val definitions = MetricDefinitions(queries, mapper)

    @Test fun `전체는 Web Android OR 조건이고 플랫폼 없음과 해외는 포함하지 않는다`() {
        val all = mapper.valueToTree<tools.jackson.databind.JsonNode>(AdminMetricEvents.platformFilters("all"))
        assertThat(all[1]["subprop_op"].asText()).isEqualTo("is")
        assertThat(all[1]["subprop_value"].toList().map { it.asText() }).containsExactly("Web", "Android")
        assertThat(all.size()).isEqualTo(2)
        assertThat(definitions.activity()).hasSize(10)
        assertThat(definitions.quality("all")).hasSize(11)
        assertThat(definitions.quality("android")).hasSize(11)
        assertThat(definitions.turnReach("all")).hasSize(24)
        assertThat(definitions.chatOptions("all", listOf("A", "B"))).hasSize(8)
        assertThat(definitions.signupPlatforms()).hasSize(6)
    }

    @Test fun `리텐션과 세션 전체도 플랫폼 OR 조건을 사용하고 비용을 예약한다`() {
        val retention = definitions.retention().first().definition
        for (key in listOf("se", "re")) {
            val event = mapper.readTree(retention.parameters.single { it.first == key }.second)
            assertThat(event["filters"][1]["subprop_value"].toList().map { it.asText() }).containsExactly("Web", "Android")
        }
        for (metrics in listOf(definitions.sessions(), definitions.sessionLengths())) {
            assertThat(metrics.sumOf { it.definition.perDay }).isEqualTo(36)
            val conditions = mapper.readTree(metrics.first().definition.parameters.single { it.first == "s" }.second)
            assertThat(conditions[1]["values"].toList().map { it.asText() }).containsExactly("Web", "Android")
        }
        assertThat(definitions.mau(LocalDate.parse("2026-10-29")).definition.perDay).isEqualTo(1)
    }
}
