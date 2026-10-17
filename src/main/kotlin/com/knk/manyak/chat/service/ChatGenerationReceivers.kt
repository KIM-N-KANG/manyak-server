package com.knk.manyak.chat.service

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.DisposableBean
import org.springframework.http.MediaType
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.atomic.AtomicBoolean

/** Executor 타입의 빈을 노출하지 않아 Boot의 기본 실행기 자동 설정에 관여하지 않는다. */
@Component
class ChatGenerationReceivers(
    private val events: ChatGenerationEvents,
    @Value("\${manyak.chat.sse-executor.concurrency:64}") concurrency: Int = 64,
    @Value("\${manyak.chat.sse-executor.queue-capacity:100}") queue: Int = 100,
) : DisposableBean {
    // 실행·큐 대기 생성마다 첫 POST와 재연결 하나를 수용한다.
    // 수신기마다 플랫폼 스레드와 전용 Redis 연결 하나를 사용하며 큐 없이 즉시 거부한다.
    private val maxReceivers = (concurrency + queue) * 2
    private val executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = maxReceivers
        maxPoolSize = maxReceivers
        queueCapacity = 0
        setAllowCoreThreadTimeOut(true)
        setThreadNamePrefix("chat-receiver-")
        setTaskDecorator(MdcTaskDecorator())
        initialize()
    }

    fun open(chatId: String, generationId: String): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MILLIS)
        val connected = AtomicBoolean(true)
        emitter.onCompletion { connected.set(false) }
        emitter.onError { connected.set(false) }
        emitter.onTimeout { connected.set(false); emitter.complete() }
        executor.execute {
            try {
                events.receive(chatId, generationId, connected::get) { name, data ->
                    emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON))
                }
                emitter.complete()
            } catch (ex: Exception) {
                emitter.completeWithError(ex)
            } finally { connected.set(false) }
        }
        return emitter
    }

    override fun destroy() = executor.shutdown()

    companion object {
        // 저장 진입 후에는 생성 마감이 적용되지 않는다. 종료는 이벤트 또는 5초마다 키 확인으로 결정한다.
        const val SSE_TIMEOUT_MILLIS = 0L
    }
}
