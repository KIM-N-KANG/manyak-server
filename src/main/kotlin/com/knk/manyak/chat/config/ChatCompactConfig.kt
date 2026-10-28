package com.knk.manyak.chat.config

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class ChatCompactSettings(
    @Value("\${manyak.chat.compact.enabled:false}") val enabled: Boolean,
    @Value("\${manyak.chat.compact.interval-turns:10}") val intervalTurns: Int,
    @Value("\${manyak.chat.compact.timeout-seconds:300}") timeoutSeconds: Long,
) {
    val timeout: Duration = Duration.ofSeconds(timeoutSeconds)
    init {
        require(intervalTurns >= 1) { "chat compact interval-turns must be at least 1" }
        require(timeoutSeconds > 0) { "chat compact timeout-seconds must be positive" }
    }
}

@Configuration
class ChatCompactConfig {
    @Bean(name = ["chatCompactExecutor"], destroyMethod = "close")
    fun chatCompactExecutor() = SimpleAsyncTaskExecutor("chat-compact-").apply {
        setVirtualThreads(true)
        setTaskDecorator(MdcTaskDecorator())
    }
}
