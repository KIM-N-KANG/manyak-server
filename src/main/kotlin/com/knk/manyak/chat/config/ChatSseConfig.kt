package com.knk.manyak.chat.config

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
class ChatSseConfig {

    @Bean(name = ["chatSseExecutor"])
    fun chatSseExecutor(
        @Value("\${manyak.chat.sse-executor.concurrency:64}") concurrency: Int,
        @Value("\${manyak.chat.sse-executor.queue-capacity:100}") queue: Int,
    ): Executor {
        require(concurrency >= 1) { "chat SSE concurrency must be at least 1" }
        require(queue >= 0) { "chat SSE queue-capacity must be nonnegative" }
        return ThreadPoolTaskExecutor().apply {
            // ThreadPoolExecutor는 큐가 찰 때까지 core 이상 늘지 않으므로 core=max로 고정한다.
            corePoolSize = concurrency
            maxPoolSize = concurrency
            queueCapacity = queue
            setAllowCoreThreadTimeOut(true)
            setThreadNamePrefix("chat-sse-")
            // 비동기 워커에도 요청 MDC(request_id 등)를 전파해 로그 상관관계를 유지한다.
            setTaskDecorator(MdcTaskDecorator())
            initialize()
        }
    }
}
