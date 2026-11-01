package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.ChatCompactAiClient
import com.knk.manyak.chat.client.ChatCompactAiRequest
import com.knk.manyak.chat.config.ChatCompactSettings
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Executor
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder

/** 이어쓰기 커밋당 한 번 발행한다. 재생성에서는 발행하지 않는다. */
data class ChatTurnCommitted(val chatId: Long, val publicId: UUID, val storyId: Long, val through: Int, val storyPublicId: UUID? = null)

@Component
class ChatCompactService(
    private val settings: ChatCompactSettings,
    @Qualifier("chatCompactExecutor") private val executor: Executor,
    private val chats: StoryChatRepository,
    private val messages: StoryMessageRepository,
    private val stories: StoryRepository,
    private val starts: StoryStartSettingRepository,
    private val client: ChatCompactAiClient,
    private val recorder: AiCallRecorder,
    private val logger: StructuredLogger,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
) {
    private val read = ChatService.assemblyTransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val write = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterCommit(event: ChatTurnCommitted) {
        if (event.through % settings.intervalTurns != 0) return
        if (!settings.enabled) {
            recordResult(OUTCOME_DISABLED)
            return
        }
        try {
            executor.execute { compact(event) }
        } catch (error: Exception) {
            failed(event, event.storyPublicId, null, "START_FAILED", error)
        }
    }

    internal fun compact(event: ChatTurnCommitted) {
        var storyPublicId: UUID? = event.storyPublicId
        var callId: Long? = null
        val snapshot = try {
            requireNotNull(read.execute {
                val chat = chats.findById(event.chatId).orElseThrow()
                storyPublicId = stories.findById(chat.storyId).orElseThrow().publicId
                CompactSnapshot(
                    ChatCompactAiRequest(if (chat.summaryThroughTurn > 0) chat.summary.orEmpty() else "", ChatHistoryContext.range(
                        messages.findByChatIdOrderByMessageOrderAsc(chat.id), chat.summaryThroughTurn, event.through,
                    )),
                    chat.regeneratedCount,
                    AiTraceLink(
                        creationId = chat.creationId, storyId = storyPublicId, chatId = chat.publicId,
                        startSettingId = chat.startSettingId?.let { starts.findById(it).orElse(null)?.publicId },
                        turnNumber = event.through,
                    ),
                )
            })
        } catch (error: Exception) {
            failed(event, storyPublicId, callId, "START_FAILED", error)
            return
        }
        // block에서 던진 예외와 호출 기록 적재·전이에서 던진 예외를 구분한다.
        var aiFailure: Exception? = null
        val result = try {
            recorder.record(
                AiCallContext(AiCallFeature.CHAT_COMPACT, event.storyId, event.publicId, event.through),
                errorCode = { if (it === aiFailure) "AI_COMPACT_FAILED" else null },
                onStarted = { callId = it },
            ) {
                try {
                    client.compact(snapshot.request, snapshot.traceLink).also {
                        require(it.isNotBlank()) { "AI compact summary is blank" }
                    }
                } catch (error: Exception) {
                    aiFailure = error
                    throw error
                }
            }.result
        } catch (error: Exception) {
            failed(event, storyPublicId, callId, if (error === aiFailure) "AI_FAILED" else "CALL_LOG_FAILED", error)
            return
        }
        try {
            val reason = write.execute {
                val chat = chats.findByIdForUpdate(event.chatId)
                when {
                    chat == null || chat.deletedAt != null -> "CHAT_DELETED"
                    chat.regeneratedCount != snapshot.regeneratedCount -> "REGENERATED"
                    event.through <= chat.summaryThroughTurn -> "SUPERSEDED"
                    else -> {
                        // 더티 체킹과 @PreUpdate를 피하고 설정 변경과도 컬럼 단위로 병합한다.
                        check(chats.updateSummary(chat.id, result, event.through) == 1)
                        null
                    }
                }
            }
            emit(if (reason == null) "chat_compact_saved" else "chat_compact_discarded", event, storyPublicId, callId,
                if (reason == null) mapOf("summary_length" to result.length) else mapOf("reason" to reason))
        } catch (error: Exception) {
            failed(event, storyPublicId, callId, "SAVE_FAILED", error)
        }
    }

    private fun failed(event: ChatTurnCommitted, story: UUID?, callId: Long?, reason: String, error: Exception) =
        emit("chat_compact_failed", event, story, callId, mapOf("reason" to reason, "error" to error.javaClass.simpleName))

    private fun emit(name: String, event: ChatTurnCommitted, story: UUID?, callId: Long?, extra: Map<String, Any?>) {
        recordResult(name.removePrefix("chat_compact_"))
        runCatching {
            logger.event(name, mapOf("chat_id" to event.publicId.toString(), "story_id" to story?.toString(),
                "summary_through_turn" to event.through, "ai_call_log_id" to callId) + extra)
        }
    }

    private fun recordResult(outcome: String) {
        runCatching {
            Counter.builder(METRIC_COMPACT_RESULT)
                .description("대화 요약 결과")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment()
        }
    }

    companion object {
        const val METRIC_COMPACT_RESULT = "manyak.chat.compact.result"
        const val OUTCOME_DISABLED = "disabled"
    }

    private data class CompactSnapshot(val request: ChatCompactAiRequest, val regeneratedCount: Int, val traceLink: AiTraceLink)
}
