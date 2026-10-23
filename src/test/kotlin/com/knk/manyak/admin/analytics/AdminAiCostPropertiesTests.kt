package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.time.Duration

class AdminAiCostPropertiesTests {
    @Test fun `실제 설정의 한글 매핑과 환율 장당 원가 호출 정책을 바인딩하고 토큰 단가 경로는 없다`() {
        ApplicationContextRunner().withInitializer { context ->
            val sources = context.environment.propertySources
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).forEach(sources::addLast)
        }.withUserConfiguration(CostConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val costs = context.getBean(AdminAiCostProperties::class.java)
            assertThat(costs.usdKrwRate).isEqualByComparingTo("1400")
            assertThat(costs.chatImageUnitCostKrw).isEqualByComparingTo("48")
            assertThat(costs.traceFeatures).containsExactlyInAnyOrderEntriesOf(mapOf("채팅 턴" to "chat_response", "채팅 선택지" to "choice_generation", "스토리라인 생성" to "storyline_generation", "스토리 컴파일" to "story_completion"))
            val langfuse = context.getBean(AdminLangfuseProperties::class.java)
            assertThat(langfuse.configured()).isFalse()
            assertThat(langfuse.requestTimeout).isEqualTo(Duration.ofSeconds(10))
            assertThat(langfuse.callInterval).isEqualTo(Duration.ofSeconds(1))
            assertThat(langfuse.todayTtl).isEqualTo(Duration.ofMinutes(10))
            assertThat(langfuse.yesterdayTtl).isEqualTo(Duration.ofHours(1))
            assertThat(langfuse.cooldown).isEqualTo(Duration.ofSeconds(60))
            assertThat(langfuse.maxCalls).isEqualTo(6)
            assertThat(langfuse.maxDays).isEqualTo(3)
            assertThat(langfuse.rowLimit).isEqualTo(1000)
            assertThat(ClassPathResource("application.yml").inputStream.bufferedReader().use { it.readText() }).doesNotContain("ai-prices:", "input-million-krw", "output-million-krw")
        }
    }
    @EnableConfigurationProperties(AdminAiCostProperties::class, AdminLangfuseProperties::class)
    class CostConfiguration
}
