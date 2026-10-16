package com.knk.manyak.chat.service

import com.knk.manyak.global.observability.MdcTaskDecorator
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 채팅 마감만 예약한다. 실행기를 빈으로 노출하지 않아 Boot 기본 taskScheduler를 유지한다. */
@Component
class ChatGenerationDeadlines {
    private val scheduler = ScheduledThreadPoolExecutor(2) { task ->
        Thread(task, "chat-generation-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val decorator = MdcTaskDecorator()

    fun schedule(task: Runnable, delayMillis: Long): ScheduledFuture<*> =
        scheduler.schedule(decorator.decorate(task), delayMillis, TimeUnit.MILLISECONDS)

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }
}
