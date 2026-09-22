package com.knk.manyak.push.config

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * 푸시 발송용 비동기 실행기(KNK-1375).
 *
 * **왜 전용 실행기가 필요한가.** 이 앱에는 실행기를 지정하지 않은 `@Async`가 쓸 실행기가 없다. 두 단계로
 * 어긋나 있다(2026-09-21 실측).
 *
 * 1. Boot의 task 자동 구성이 `@ConditionalOnMissingBean(Executor)`라, `chatSseExecutor` 빈 때문에
 *    `applicationTaskExecutor`를 만들지 않는다. 테스트에서 그 이름으로 주입하면 없는 빈이라 실패한다.
 * 2. 그러면 Spring이 `TaskExecutor` 타입 빈 하나를 찾아보는데 `chatSseExecutor`와 `taskScheduler` **둘**이
 *    걸리고 `taskExecutor`라는 이름의 빈도 없다. 고를 수 없으니 기동 로그에 `More than one TaskExecutor
 *    bean found ...`를 남기고 `SimpleAsyncTaskExecutor`로 떨어진다. **이 경고는 이 변경 전부터 있었다.**
 *
 * 그 fallback에는 [MdcTaskDecorator]가 없어 워커 스레드의 MDC가 비고, 그 스레드에서 만든 다운스트림 호출은
 * 상관 헤더를 실어 보내지 못한다. 작업마다 새 스레드를 만들기도 한다.
 *
 * 기본 실행기를 여기서 정의하지 않는 이유는 범위다. 그렇게 하면 채팅, 피드백, 색인을 포함한 이 앱의 모든
 * `@Async`가 한꺼번에 이 풀로 옮겨 온다. 푸시 경로만 바로잡고 앱 전체는 KNK-1392에서 다룬다.
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
