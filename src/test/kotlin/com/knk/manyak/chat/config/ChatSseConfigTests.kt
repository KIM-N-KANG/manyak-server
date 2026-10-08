package com.knk.manyak.chat.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

class ChatSseConfigTests {
    private fun runner() = ApplicationContextRunner().withUserConfiguration(ChatSseConfig::class.java)

    @Test
    fun `기본 실행기는 큐보다 먼저 64개까지 늘고 유휴 코어 스레드는 회수한다`() {
        runner().run { context ->
            assertThat(context).hasNotFailed()
            val executor = context.getBean("chatSseExecutor", ThreadPoolTaskExecutor::class.java)
            assertThat(executor.corePoolSize).isEqualTo(64)
            assertThat(executor.maxPoolSize).isEqualTo(64)
            assertThat(executor.threadPoolExecutor.queue.remainingCapacity()).isEqualTo(100)
            assertThat(executor.threadPoolExecutor.allowsCoreThreadTimeOut()).isTrue()
            assertThat(executor.threadPoolExecutor.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60)
        }
    }

    @Test
    fun `외부 설정으로 실행기 크기를 바꿀 수 있다`() {
        runner().withPropertyValues(
            "manyak.chat.sse-executor.core-pool-size=8",
            "manyak.chat.sse-executor.max-pool-size=12",
            "manyak.chat.sse-executor.queue-capacity=25",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val executor = context.getBean("chatSseExecutor", ThreadPoolTaskExecutor::class.java)
            assertThat(executor.corePoolSize).isEqualTo(8)
            assertThat(executor.maxPoolSize).isEqualTo(12)
            assertThat(executor.threadPoolExecutor.queue.remainingCapacity()).isEqualTo(25)
        }
    }

    @Test
    fun `큐 용량 0은 대기열 없는 실행기로 허용한다`() {
        runner().withPropertyValues("manyak.chat.sse-executor.queue-capacity=0").run { context ->
            assertThat(context).hasNotFailed()
            val executor = context.getBean("chatSseExecutor", ThreadPoolTaskExecutor::class.java)
            assertThat(executor.threadPoolExecutor.queue).isInstanceOf(SynchronousQueue::class.java)
        }
    }

    @ParameterizedTest
    @CsvSource("0,64,100", "-1,64,100", "64,63,100", "64,64,-1")
    fun `잘못된 크기는 기동을 거부한다`(core: Int, max: Int, queue: Int) {
        runner().withPropertyValues(
            "manyak.chat.sse-executor.core-pool-size=$core",
            "manyak.chat.sse-executor.max-pool-size=$max",
            "manyak.chat.sse-executor.queue-capacity=$queue",
        ).run { context -> assertThat(context).hasFailed() }
    }
}
