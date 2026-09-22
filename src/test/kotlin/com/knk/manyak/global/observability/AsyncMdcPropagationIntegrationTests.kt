package com.knk.manyak.global.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import com.knk.manyak.push.config.PushAsyncConfig
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `@Async` 기본 실행기의 MDC 전파(KNK-1375).
 *
 * [MdcTaskDecorator]는 오래전부터 있었지만 `chatSseExecutor`에만 수동으로 붙어 있었다. 실행기를 지정하지 않은
 * `@Async`(스토리 완성 푸시 리스너 등)는 Boot가 자동 구성한 `applicationTaskExecutor`에서 도는데, 여기에는
 * decorator가 없어 워커 스레드의 MDC가 비었다. 그래서 그 스레드에서 만든 다운스트림 호출은 상관 헤더를
 * 실어 보내지 못했고, 서버와 알림 서비스 로그를 같은 요청으로 묶을 수 없었다.
 *
 * 컨텍스트를 새로 띄우지 않으려고 가장 흔한 `@SpringBootTest` 형태를 그대로 쓴다(캐시 재사용).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncMdcPropagationIntegrationTests {

    @Autowired
    @Qualifier(PushAsyncConfig.PUSH_EXECUTOR)
    private lateinit var pushExecutor: Executor

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `푸시 비동기 작업은 제출 스레드의 상관 식별자를 본다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_async_propagation")
        MDC.put(MdcKeys.SESSION_ID, "session_async_propagation")

        val seen = CompletableFuture<Map<String, String?>>()
        pushExecutor.execute {
            seen.complete(mapOf(MdcKeys.REQUEST_ID to MDC.get(MdcKeys.REQUEST_ID), MdcKeys.SESSION_ID to MDC.get(MdcKeys.SESSION_ID)))
        }

        assertThat(seen.get(5, TimeUnit.SECONDS))
            .containsEntry(MdcKeys.REQUEST_ID, "req_async_propagation")
            .containsEntry(MdcKeys.SESSION_ID, "session_async_propagation")
    }

    @Test
    fun `상관 식별자가 없으면 워커에 이전 작업의 값이 남지 않는다`() {
        MDC.clear()

        val seen = CompletableFuture<String?>()
        pushExecutor.execute { seen.complete(MDC.get(MdcKeys.REQUEST_ID)) }

        // 스레드풀은 재사용되므로 값이 남으면 다음 요청의 로그가 남의 request_id를 달고 나간다.
        assertThat(seen.get(5, TimeUnit.SECONDS)).isNull()
    }
}
