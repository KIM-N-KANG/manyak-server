package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.math.BigDecimal

class AdminAiPricesTests {
    @Test
    fun `실제 설정의 모델 5개는 점을 포함한 키와 백만 토큰당 원화 단가를 그대로 바인딩한다`() {
        ApplicationContextRunner()
            .withInitializer { context ->
                val sources = context.environment.propertySources
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
                    .forEach(sources::addLast)
            }
            .withUserConfiguration(PriceConfiguration::class.java)
            .run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(AdminAiPrices::class.java)
                val prices = context.getBean(AdminAiPrices::class.java)
                val expected = mapOf(
                    "deepseek-flash" to TokenPrice(BigDecimal("210"), BigDecimal("840")),
                    "gemini-3.7-flash" to TokenPrice(BigDecimal("1050"), BigDecimal("5250")),
                    "gemini-3.8-flash" to TokenPrice(BigDecimal("1050"), BigDecimal("5250")),
                    "gpt-6-luna" to TokenPrice(BigDecimal("140"), BigDecimal("700")),
                    "gpt-6.1-sol" to TokenPrice(BigDecimal("2800"), BigDecimal("14000")),
                )
                assertThat(prices.models).containsExactlyInAnyOrderEntriesOf(expected)
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminAiPrices::class)
    class PriceConfiguration
}
