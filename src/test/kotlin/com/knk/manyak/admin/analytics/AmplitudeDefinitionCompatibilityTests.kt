package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.nio.file.Path

class AmplitudeDefinitionCompatibilityTests {
    @TestFactory fun `비식별 운영 응답과 신규 조건의 대역 구조를 모든 실제 디코더로 검증한다`(): List<DynamicTest> {
        val inventory = AmplitudeDefinitionInventory()
        val responses = responses(inventory)
        // 신규 조건은 실호출하지 않는다. 같은 엔드포인트·지표의 기존 비식별 응답 형태만 대역으로 재사용한다.
        val aliases = inventory.mapper.readTree(ClassPathResource("amplitude/app-overseas-shape-aliases.json").inputStream.bufferedReader().use { it.readText() })
        return inventory.entries.map { (hash, entry) -> DynamicTest.dynamicTest(entry.definition.metric + ":" + hash.takeLast(64).take(8)) {
            val response = requireNotNull(responses[hash] ?: aliases[hash]?.let { responses[it.asText()] }) { "응답 형태 fixture 누락: $hash" }
            assertThat(response).describedAs("실제 응답 구조 fixture: " + hash).isNotNull()
            assertThat(response["status"].asInt()).isEqualTo(200)
            entry.decode(response["data"])
        } }
    }
    private fun responses(inventory: AmplitudeDefinitionInventory) = inventory.mapper.readTree(ClassPathResource("amplitude/definitions.json").inputStream.bufferedReader().use { it.readText() })
    @Test fun `7월 1일 초기 백필의 평균과 확정 리텐션 구조도 해석한다`() {
        val inventory = AmplitudeDefinitionInventory()
        val responses = responses(inventory)
        val aliases = inventory.mapper.readTree(ClassPathResource("amplitude/app-overseas-shape-aliases.json").inputStream.bufferedReader().use { it.readText() })
        val date = java.time.LocalDate.parse("2026-07-01")
        val range = AnalyticsRange(date, date)
        val definitions = MetricDefinitions(AnalyticsQueryFactory(inventory.mapper), inventory.mapper)
        for (metric in listOf(definitions.sessions().first(), definitions.retention().first(), definitions.activity().first { it.definition.metric == "activity_messages" })) {
            val key = "historical-" + metric.definition.metric + "-" + metric.definition.hash
            val result = metric.decode((responses[key] ?: aliases[key]?.let { responses[it.asText()] } ?: error("응답 형태 fixture 누락: $key"))["data"], range)
            assertThat(result.keys).containsExactly(date)
        }
    }
    @Test fun `모든 운영 조회 정의를 목록화한다`() {
        val inventory = AmplitudeDefinitionInventory()
        assertThat(inventory.entries.size).isGreaterThan(80)
        System.getenv("AMPLITUDE_PROBE_EXPORT")?.let { destination ->
            val rows = inventory.entries.map { (hash, entry) -> mapOf("hash" to hash, "metric" to entry.definition.metric, "path" to entry.definition.path,
                "parameters" to entry.definition.parameters.map { listOf(it.first, it.second) }, "perDay" to entry.definition.perDay,
                "from" to entry.range.from.toString(), "to" to entry.range.to.toString(), "uses" to entry.uses) }
            Files.writeString(Path.of(destination), inventory.mapper.writeValueAsString(rows))
        }
    }
}
