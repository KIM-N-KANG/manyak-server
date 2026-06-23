package com.knk.manyak.chat.config

import com.knk.manyak.global.observability.MdcTaskDecorator
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
            // 비동기 워커에도 요청 MDC(request_id 등)를 전파해 로그 상관관계를 유지한다.
            setTaskDecorator(MdcTaskDecorator())
            initialize()
        }
}
