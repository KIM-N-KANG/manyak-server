package com.knk.manyak.global.config

import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.micrometer.registry.otlp.OtlpMetricsSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.env.RandomValuePropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.util.UUID

class OtlpInstanceIdentityTests {
    private fun runner() = ApplicationContextRunner()
        .withInitializer { context ->
            val sources = context.environment.propertySources
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            RandomValuePropertySource.addToEnvironment(context.environment)
            YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
                .forEach(sources::addLast)
        }
        .withConfiguration(AutoConfigurations.of(
            MetricsAutoConfiguration::class.java,
            OtlpMetricsExportAutoConfiguration::class.java,
        ))
        // OTLP 자동구성은 그대로 쓰되 종료 시 flush까지 외부로 전송하지 않는다.
        .withBean(OtlpMetricsSender::class.java, { mock(OtlpMetricsSender::class.java) })

    @Test
    fun `서로 다른 앱 실행은 다른 OTLP 인스턴스를 가지며 실행 중에는 유지한다`() {
        val ids = mutableListOf<String>()
        repeat(2) {
            runner().withPropertyValues("MANYAK_OTLP_METRICS_ENABLED=true").run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(OtlpMeterRegistry::class.java)
                val config = context.getBean(OtlpConfig::class.java)
                val id = config.resourceAttributes()["service.instance.id"]
                assertThat(id).isNotBlank()
                UUID.fromString(id)
                repeat(3) {
                    assertThat(config.resourceAttributes()["service.instance.id"]).isEqualTo(id)
                }
                ids.add(id!!)
            }
        }
        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `명시한 인스턴스와 서비스 이름 및 다른 resource 속성을 보존한다`() {
        runner().withPropertyValues(
            "MANYAK_OTLP_METRICS_ENABLED=true",
            "management.opentelemetry.resource-attributes[service.instance.id]=task-unique-id",
            "management.opentelemetry.resource-attributes[service.name]=manyak-server-local",
            "management.opentelemetry.resource-attributes[deployment.environment.name]=local",
        ).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(OtlpConfig::class.java).resourceAttributes())
                .containsEntry("service.instance.id", "task-unique-id")
                .containsEntry("service.name", "manyak-server-local")
                .containsEntry("deployment.environment.name", "local")
        }
    }

    @Test
    fun `기본 서비스 이름과 누적 카운터 전송 방식을 유지한다`() {
        runner().withPropertyValues("MANYAK_OTLP_METRICS_ENABLED=true").run { context ->
            val config = context.getBean(OtlpConfig::class.java)
            assertThat(config.resourceAttributes()).containsEntry("service.name", "manyak-server")
            assertThat(config.aggregationTemporality().name).isEqualTo("CUMULATIVE")
        }
    }

    @Test
    fun `기본 설정에서는 OTLP 전송을 켜지 않는다`() {
        runner().run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(OtlpMeterRegistry::class.java)
        }
    }
}
