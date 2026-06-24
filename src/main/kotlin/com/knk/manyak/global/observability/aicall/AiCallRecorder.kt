package com.knk.manyak.global.observability.aicall

import com.knk.manyak.global.observability.MdcKeys
import io.sentry.Sentry
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * AI 호출을 감싸 ai_call_logs에 적재한다.
 *
 * 호출 직전 STARTED로 적재하고, [block] 실행 결과에 따라 SUCCEEDED/FAILED로 전이한다.
 * latency는 여기서 측정하고, request_id·session_id·anonymous_id_hash는 MDC에서 읽는다.
 * (chat_response처럼 chatSseExecutor 워커에서 호출돼도 MdcTaskDecorator가 MDC를 전파하므로 동일하게 동작한다.)
 *
 * 반환한 [RecordedAiCall.aiCallLogId]를 호출부가 ai_response_saved 등 로그에 실어 상관관계를 잇는다.
 */
@Component
class AiCallRecorder(
    private val repository: AiCallLogRepository,
    @param:Value("\${spring.application.name:manyak-server}")
    private val callerService: String,
) {
    /**
     * [block]을 실행하며 호출 이력을 적재한다. 성공 시 결과와 적재 id를 반환하고,
     * 실패 시 FAILED로 적재한 뒤 원래 예외를 그대로 전파한다.
     *
     * @param errorCode 실패 예외를 error_code로 매핑한다. 기본은 예외 클래스 이름.
     *  도메인 오류 코드를 가진 호출부(예: ChatTurnAiException.code)는 직접 넘긴다.
     * @param meta 성공한 [block] 결과에서 AI 응답 meta를 추출한다(없으면 null). 추출값은 SUCCEEDED 전이와
     *  같은 저장에 반영돼 추가 UPDATE·동시성 갭이 없다. chat도 completed 결과(ChatTurnAiResult)에 meta가
     *  실려 오므로 동일하게 처리한다. 추출이 던져도 관측이 비즈니스 호출을 깨지 않도록 격리한다.
     */
    fun <T> record(
        context: AiCallContext,
        errorCode: (Throwable) -> String? = { it::class.simpleName },
        onFailure: (aiCallLogId: Long, throwable: Throwable) -> Unit = { _, _ -> },
        meta: (T) -> AiCallMeta? = { null },
        block: () -> T,
    ): RecordedAiCall<T> {
        val log = repository.save(started(context))
        Sentry.addBreadcrumb("ai_call started: ${context.feature.value}", "ai")
        val startNanos = System.nanoTime()
        try {
            val result = block()
            // meta 추출·반영 실패가 성공한 AI 호출을 FAILED로 둔갑시키지 않도록 격리한다(관측 < 비즈니스).
            runCatching { meta(result)?.let(log::applyMeta) }
            log.markSucceeded(latencyMs = elapsedMs(startNanos))
            repository.save(log)
            Sentry.addBreadcrumb("ai_call succeeded: ${context.feature.value}", "ai")
            return RecordedAiCall(result, log.id)
        } catch (throwable: Throwable) {
            // error_code도 컬럼 길이로 자른다. 자르지 않으면 긴 코드(예: 긴 ChatTurnAiException.code)에서
            // save가 length 위반을 던져 원래 AI 예외를 가리고 구조화 에러 relay가 깨진다.
            log.markFailed(
                latencyMs = elapsedMs(startNanos),
                errorCode = errorCode(throwable)?.take(ERROR_CODE_MAX_LENGTH),
            )
            repository.save(log)
            Sentry.addBreadcrumb("ai_call failed: ${context.feature.value}", "ai")
            // 적재된 호출 id로 후처리(예: Sentry 캡처 후 sentry_event_id 연결)할 기회를 준다.
            // 후처리(관측) 실패가 원래 예외를 가리지 않도록 격리한 뒤 전파한다.
            runCatching { onFailure(log.id, throwable) }
            throw throwable
        }
    }

    /**
     * 적재된 호출의 turn_index를 사후에 채운다.
     *
     * chat_response의 실제 turn 번호는 persistTurn이 DB에서 확정하므로, 그 뒤에 호출해야
     * 같은 채팅에 동시 요청이 겹쳐도 ai_call_logs.turn_index가 저장된 턴과 어긋나지 않는다.
     */
    fun attachTurnIndex(aiCallLogId: Long, turnIndex: Int) {
        repository.findById(aiCallLogId).ifPresent { log ->
            log.turnIndex = turnIndex
            repository.save(log)
        }
    }

    /**
     * 적재된 호출에 Sentry 이벤트 id를 연결한다. 실패한 AI 호출을 캡처한 뒤 호출해
     * ai_call_logs와 Sentry 이벤트를 상호 참조할 수 있게 한다.
     */
    fun attachSentryEventId(aiCallLogId: Long, sentryEventId: String) {
        repository.findById(aiCallLogId).ifPresent { log ->
            log.sentryEventId = sentryEventId
            repository.save(log)
        }
    }

    private fun started(context: AiCallContext) = AiCallLog(
        // request_id는 NOT NULL이므로 MDC에 없으면 최소 "unknown"으로 채운다.
        // 클라이언트가 헤더로 보낸 값이 컬럼 길이를 넘어도 적재(=관측)가 비즈니스 호출을 깨지 않도록 자른다.
        requestId = (MDC.get(MdcKeys.REQUEST_ID) ?: UNKNOWN).take(IDENTIFIER_MAX_LENGTH),
        callerService = callerService,
        feature = context.feature,
        // session_id·anonymous_id_hash는 헤더 누락 시 필터가 "unknown"으로 채우므로, 분석을 위해 null로 정규화한다.
        anonymousIdHash = cleanMdc(MdcKeys.ANONYMOUS_ID_HASH),
        sessionId = cleanMdc(MdcKeys.SESSION_ID),
        storyId = context.storyId,
        chatId = context.chatId,
        // turn_index는 적재 시점엔 비워 두고, persistTurn이 실제 턴을 확정한 뒤 attachTurnIndex로 채운다.
    )

    private fun cleanMdc(key: String): String? =
        MDC.get(key)?.takeIf { it.isNotBlank() && it != UNKNOWN }?.take(IDENTIFIER_MAX_LENGTH)

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    companion object {
        private const val UNKNOWN = "unknown"

        // 컬럼 길이와 일치시켜, 초과 입력이 적재 실패로 비즈니스 호출을 막지 않도록 자른다.
        private const val IDENTIFIER_MAX_LENGTH = 128 // request_id·session_id VARCHAR(128)
        private const val ERROR_CODE_MAX_LENGTH = 100 // error_code VARCHAR(100)
    }
}

/**
 * 호출부가 아는, MDC에 없는 식별자. feature는 필수이고 나머지는 호출 종류에 따라 채운다.
 * (storyline_generation은 story_id 없음, chat_response만 chat_id·turn_index가 있음)
 */
data class AiCallContext(
    val feature: AiCallFeature,
    val storyId: Long? = null,
    val chatId: UUID? = null,
)

/** 적재된 호출의 결과와 ai_call_log_id(상관관계 연결용). */
data class RecordedAiCall<T>(
    val result: T,
    val aiCallLogId: Long,
)
