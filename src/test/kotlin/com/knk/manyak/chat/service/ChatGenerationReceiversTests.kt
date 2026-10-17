package com.knk.manyak.chat.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import com.knk.manyak.chat.config.ChatSseConfig
import java.util.concurrent.CountDownLatch
import org.springframework.core.task.TaskRejectedException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.mockito.Mockito
import org.slf4j.MDC
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ChatGenerationReceiversTests {
    @Test fun `수신 실행기는 생성 실행기와 별개이며 큐 없이 생성 수용량의 두 배로 제한하고 MDC를 넘긴다`() {
        val events = Mockito.mock(ChatGenerationEvents::class.java)
        val seen = CompletableFuture<Map<String, String>>()
        Mockito.doAnswer {
            seen.complete(MDC.getCopyOfContextMap())
            null
        }.`when`(events).receive(Mockito.anyString(), Mockito.anyString(), Mockito.any<() -> Boolean>() ?: { true }, Mockito.any<(String, String) -> Unit>() ?: { _, _ -> })
        val receivers = ChatGenerationReceivers(events)
        try {
            val pool = ReflectionTestUtils.getField(receivers, "executor") as ThreadPoolTaskExecutor
            assertThat(pool.corePoolSize).isEqualTo((64 + 100) * 2)
            assertThat(pool.maxPoolSize).isEqualTo((64 + 100) * 2)
            assertThat(pool.queueCapacity).isZero()
            MDC.put("request_id", "req_receiver")
            val emitter = receivers.open("chat", "generation")
            assertThat(emitter.timeout).isZero()
            MDC.clear()
            assertThat(seen.get(3, TimeUnit.SECONDS)["request_id"]).isEqualTo("req_receiver")
            assertThat(receivers).isNotInstanceOf(java.util.concurrent.Executor::class.java)
        } finally { MDC.clear(); receivers.destroy() }
    }
    @ParameterizedTest
    @CsvSource("64,100", "2,3", "129,2")
    fun `생성 큐에 자리가 있는 첫 POST를 수신 상한으로 거부하지 않고 재연결 여유를 제공한다`(concurrency: Int, queue: Int) {
        val release = CountDownLatch(1)
        val events = Mockito.mock(ChatGenerationEvents::class.java)
        Mockito.doAnswer { release.await(); null }.`when`(events).receive(
            Mockito.anyString(), Mockito.anyString(),
            Mockito.any<() -> Boolean>() ?: { true },
            Mockito.any<(String, String) -> Unit>() ?: { _, _ -> },
        )
        val receivers = ChatGenerationReceivers(events, concurrency, queue)
        val generation = ChatSseConfig().chatSseExecutor(concurrency, queue) as ThreadPoolTaskExecutor
        try {
            val pool = ReflectionTestUtils.getField(receivers, "executor") as ThreadPoolTaskExecutor
            repeat(concurrency + queue) { index ->
                if (index >= concurrency) assertThat(generation.threadPoolExecutor.queue.remainingCapacity()).isPositive()
                receivers.open("chat-$index", "generation") // 첫 POST와 같은 제출 순서
                generation.execute { release.await() }
            }
            assertThat(pool.maxPoolSize).isEqualTo((concurrency + queue) * 2)
            assertThat(generation.threadPoolExecutor.queue.remainingCapacity()).isZero()
            repeat(concurrency + queue) { receivers.open("reconnect-$it", "generation") }
            assertThat(pool.threadPoolExecutor.queue).isEmpty()
            assertThatThrownBy { receivers.open("excess", "generation") }.isInstanceOf(TaskRejectedException::class.java)
        } finally {
            release.countDown()
            receivers.destroy()
            generation.shutdown()
        }
    }
}
