package com.knk.manyak.global.config

import io.lettuce.core.resource.Delay
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
@Profile("prod")
@EnableConfigurationProperties(RedisReconnectProperties::class)
class RedisClientConfig {
    @Bean
    fun redisReconnectDelay(properties: RedisReconnectProperties): ClientResourcesBuilderCustomizer =
        ClientResourcesBuilderCustomizer { builder ->
            // KNK-1528: DNS 전환 후 다음 시도가 기본 최대 30초 뒤로 밀리지 않게 한다.
            builder.reconnectDelay(
                Delay.exponential(properties.reconnectMinDelay, properties.reconnectMaxDelay, 2, TimeUnit.SECONDS),
            )
        }
}

@ConfigurationProperties("manyak.redis")
data class RedisReconnectProperties(
    val reconnectMinDelay: Duration = Duration.ofSeconds(1),
    val reconnectMaxDelay: Duration = Duration.ofSeconds(2),
)
