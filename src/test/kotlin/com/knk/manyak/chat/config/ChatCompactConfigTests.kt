package com.knk.manyak.chat.config

import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ChatCompactConfigTests {
    @Test fun `요약 설정은 양수 간격과 시간 한도를 검증한다`() {
        assertThatThrownBy { ChatCompactSettings(false, 0, 300) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ChatCompactSettings(false, 10, 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ChatCompactSettings(false, -1, 300) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ChatCompactSettings(false, 10, 300).timeout.seconds).isEqualTo(300)
    }
    @Test fun `요약 설정이 잘못되면 비활성 상태에서도 컨텍스트 기동을 거절한다`() {
        for (property in listOf("manyak.chat.compact.interval-turns=0", "manyak.chat.compact.timeout-seconds=0")) {
            ApplicationContextRunner()
                .withUserConfiguration(ChatCompactSettings::class.java)
                .withPropertyValues("manyak.chat.compact.enabled=false", property)
                .run { context -> assertThat(context).hasFailed() }
        }
    }

    @Test fun `요약 전용 실행기는 가상 스레드에 요청 MDC를 전달한다`() {
        val executor = ChatCompactConfig().chatCompactExecutor()
        try {
            MDC.put("request_id", "compact-request")
            val result = CompletableFuture<Pair<Boolean, String?>>()
            executor.execute { result.complete(Thread.currentThread().isVirtual to MDC.get("request_id")) }
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(true to "compact-request")
        } finally { MDC.clear(); executor.close() }
    }
}
