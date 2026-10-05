package com.knk.manyak.global.config

import io.lettuce.core.resource.ClientResources
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.time.Duration

class RedisClientConfigTests {
    private fun runner(profile: String) = ApplicationContextRunner()
        .withInitializer { it.environment.setActiveProfiles(profile) }
        .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration::class.java))
        .withUserConfiguration(RedisClientConfig::class.java)

    // prod DB/Flyway 설정은 로드하지 않고 실제 prod YAML의 Redis 설정만 자동 구성에 바인딩한다.
    private fun productionRunner(): ApplicationContextRunner {
        val properties = YamlPropertiesFactoryBean()
            .apply { setResources(ClassPathResource("application-prod.yml")) }.getObject()!!
        val redisProperties = properties.stringPropertyNames()
            .filter { it.startsWith("spring.data.redis.") || it.startsWith("manyak.redis.") }
            .map { "$it=${properties.getProperty(it)}" }.toTypedArray()
        return runner("prod").withPropertyValues(*redisProperties)
    }

    @Test
    fun `운영 자동 구성 빈에 재연결 상한과 두 timeout이 반영된다`() {
        productionRunner().run {
            assertThat(it).hasNotFailed().hasSingleBean(ClientResources::class.java)
            val resources = it.getBean(ClientResources::class.java)
            val delay = resources.reconnectDelay()
            assertThat(delay.createDelay(1)).isEqualTo(Duration.ofSeconds(1))
            for (attempt in listOf(2L, 5L, 16L, 100L)) {
                assertThat(delay.createDelay(attempt)).isEqualTo(Duration.ofSeconds(2))
            }
            val config = it.getBean(LettuceConnectionFactory::class.java).clientConfiguration
            assertThat(config.clientResources).hasValue(resources)
            assertThat(config.commandTimeout).isEqualTo(Duration.ofSeconds(2))
            assertThat(config.clientOptions.orElseThrow().socketOptions.connectTimeout)
                .isEqualTo(Duration.ofSeconds(1))
        }
    }

    @Test
    fun `운영 재연결 상한은 외부 설정으로 바꿀 수 있다`() {
        productionRunner().withPropertyValues("manyak.redis.reconnect-max-delay=3s").run {
            assertThat(it).hasNotFailed()
            assertThat(it.getBean(ClientResources::class.java).reconnectDelay().createDelay(100))
                .isEqualTo(Duration.ofSeconds(3))
        }
    }

    @Test
    fun `test에서는 운영 재연결 설정을 등록하지 않는다`() {
        runner("test").run {
            assertThat(it).hasNotFailed().doesNotHaveBean(RedisClientConfig::class.java)
            assertThat(it.getBean(ClientResources::class.java).reconnectDelay().createDelay(100))
                .isEqualTo(Duration.ofSeconds(30))
        }
    }
}
