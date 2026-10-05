package com.knk.manyak.push.config

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * 푸시 발송용 비동기 실행기(KNK-1375).
 *
 * 기본 `@Async`는 AsyncConfig의 taskExecutor를 사용한다. 푸시는 전용 실행기를 유지해
 * 피드백·검색 색인 등 기본 비동기 작업과 발송 부하를 분리하고 요청 MDC를 전파한다.
 *
 * 발송은 FCM 왕복을 기다리는 IO 작업이라 스레드를 적게 두고 큐로 흡수한다.
 */
@Configuration
class PushAsyncConfig {

    @Bean(name = [PUSH_EXECUTOR])
    fun pushExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 8
            queueCapacity = 100
            setThreadNamePrefix("push-")
            // 요청 스레드의 request_id 등을 워커로 옮긴다. 알림 서비스 호출에 그대로 실려 나간다.
            setTaskDecorator(MdcTaskDecorator())
            initialize()
        }

    companion object {
        const val PUSH_EXECUTOR = "pushExecutor"
    }
}
