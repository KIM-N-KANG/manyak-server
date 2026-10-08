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
        @Value("\${manyak.chat.sse-executor.core-pool-size:64}") core: Int,
        @Value("\${manyak.chat.sse-executor.max-pool-size:64}") max: Int,
        @Value("\${manyak.chat.sse-executor.queue-capacity:100}") queue: Int,
    ): Executor {
        require(core >= 1) { "chat SSE core-pool-size must be at least 1" }
        require(max >= core) { "chat SSE max-pool-size must be at least core-pool-size" }
        require(queue >= 0) { "chat SSE queue-capacity must be nonnegative" }
        return ThreadPoolTaskExecutor().apply {
            // ThreadPoolExecutor는 큐가 찰 때까지 core 이상 늘지 않아 기본 core=max로 즉시 확장한다.
            corePoolSize = core
            maxPoolSize = max
            queueCapacity = queue
            setAllowCoreThreadTimeOut(true)
            setThreadNamePrefix("chat-sse-")
            // 비동기 워커에도 요청 MDC(request_id 등)를 전파해 로그 상관관계를 유지한다.
            setTaskDecorator(MdcTaskDecorator())
            initialize()
        }
    }
}
