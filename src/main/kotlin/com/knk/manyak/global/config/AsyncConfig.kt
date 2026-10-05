package com.knk.manyak.global.config

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class AsyncConfig {

    /**
     * 실행기를 지정하지 않은 `@Async`가 여러 후보 중 이름으로 선택하는 기본 실행기다.
     *
     * Boot의 applicationTaskExecutor 기본값인 코어 8개와 무제한 큐를 따른다.
     * 큐 포화로 AFTER_COMMIT 작업이 거부되어 이미 커밋된 요청이 500으로 바뀌는 것을 방지한다.
     * 요청 MDC를 워커로 전파해 로그와 다운스트림 호출의 상관관계를 유지한다.
     */
    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 8
            queueCapacity = Int.MAX_VALUE
            setThreadNamePrefix("async-")
            setTaskDecorator(MdcTaskDecorator())
            initialize()
        }
}
