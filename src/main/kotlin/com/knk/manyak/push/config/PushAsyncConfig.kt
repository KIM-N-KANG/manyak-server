package com.knk.manyak.push.config

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * 푸시 발송용 비동기 실행기(KNK-1375).
 *
 * **왜 전용 실행기가 필요한가.** 이 앱에는 `@Async`의 기본 실행기가 없다. Boot의 task 자동 구성은
 * `@ConditionalOnMissingBean(Executor)`라, `chatSseExecutor` 빈 하나 때문에 `applicationTaskExecutor`를
 * 아예 만들지 않는다. 그래서 실행기를 지정하지 않은 `@Async`는 Spring 기본값인 `SimpleAsyncTaskExecutor`로
 * 떨어지고, 거기에는 [MdcTaskDecorator]가 없어 워커 스레드의 MDC가 비어 있었다. 그 스레드에서 만든
 * 다운스트림 호출은 상관 헤더를 실어 보내지 못해 서버와 알림 서비스 로그를 같은 요청으로 묶을 수 없었다.
 *
 * 기본 실행기를 여기서 새로 정의하지 않는 이유는 범위다. 그렇게 하면 이 앱의 모든 `@Async`(채팅, 피드백
 * 등)의 스레드 모델이 한꺼번에 바뀐다. 푸시 경로만 바로잡고, 앱 전체의 기본 실행기 부재는 별도로 다룬다.
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
