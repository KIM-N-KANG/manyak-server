package com.knk.manyak.chat.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
class ChatSseConfig {

    @Bean(name = ["chatSseExecutor"])
    fun chatSseExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 16
            queueCapacity = 100
            setThreadNamePrefix("chat-sse-")
            initialize()
        }
}
