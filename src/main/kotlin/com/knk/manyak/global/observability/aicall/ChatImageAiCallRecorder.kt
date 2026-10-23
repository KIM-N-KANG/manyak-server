package com.knk.manyak.global.observability.aicall

import com.knk.manyak.global.observability.MdcKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 이미지 관측은 턴/환불 트랜잭션을 오염시키지 않도록 별도 트랜잭션과 WARN 경계를 사용한다. */
@Component
class ChatImageAiCallRecorder(private val repository: AiCallLogRepository, transactionManager: PlatformTransactionManager,
    @Value("\${spring.application.name:manyak-server}") private val callerService: String,
    private val clock: Clock = Clock.systemUTC()) {
    private val transaction = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    private val logger = LoggerFactory.getLogger(javaClass)
    class Call internal constructor(val id: Long) {
        internal val started = AtomicReference<Instant?>()
        internal val latency = AtomicReference<Long?>()
        internal val finished = AtomicBoolean(false)
    }
    fun start(storyId: Long, chatId: UUID): Call? = safely("start") {
        fun mdc(key: String) = MDC.get(key)?.takeIf { it.isNotBlank() && it != "unknown" }?.take(128)
        transaction.execute {
            val log = repository.saveAndFlush(AiCallLog(requestId = (mdc(MdcKeys.REQUEST_ID) ?: "unknown"),
                callerService = callerService, feature = AiCallFeature.CHAT_IMAGE,
                storyId = storyId, chatId = chatId, sessionId = mdc(MdcKeys.SESSION_ID), deviceIdHash = mdc(MdcKeys.DEVICE_ID_HASH), createdAt = clock.instant()))
            Call(log.id)
        }
    }
    fun bodyStarted(call: Call?) { call?.started?.compareAndSet(null, clock.instant()) }
    fun imageReceived(call: Call?, url: String, issuedUrl: String?) {
        if (call == null || url != issuedUrl) return
        val start = call.started.get() ?: return
        call.latency.compareAndSet(null, Duration.between(start, clock.instant()).toMillis().coerceAtLeast(0))
    }
    fun finish(call: Call?, turnNumber: Int? = null, errorCode: String? = null) {
        if (call == null || !call.finished.compareAndSet(false, true)) return
        safely("finish") {
            transaction.executeWithoutResult {
                val log = repository.findById(call.id).orElseThrow()
                log.status = if (errorCode == null) AiCallStatus.SUCCEEDED else AiCallStatus.FAILED
                log.turnNumber = turnNumber; log.latencyMs = call.latency.get(); log.errorCode = errorCode
                log.completedAt = clock.instant()
                repository.saveAndFlush(log)
            }
        }
    }
    private fun <T> safely(phase: String, action: () -> T): T? = try { action() } catch (e: Exception) {
        logger.warn("chat_image_ai_call_record_failed phase={} error={}", phase, e.javaClass.simpleName)
        null
    }
}
